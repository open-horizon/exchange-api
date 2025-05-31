package org.openhorizon.exchangeapi.route.node

import org.json4s.DefaultFormats
import org.{json4s, scalatest}
import org.json4s.{DefaultFormats, convertToJsonInput}
import org.json4s.AsJsonInput.stringAsJsonInput
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.{NodePolicy, NodePolicyRow, NodePolicyTQ}
import org.openhorizon.exchangeapi.table.node.error.{NodeErrorRow, NodeErrorTQ}
import org.openhorizon.exchangeapi.table.node.status.{NodeStatusRow, NodeStatusTQ}
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodeRow, NodesTQ, OneService, RegService}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode, StrConstants}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import org.scalatest.BeforeAndAfterAll
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable.{List, Map}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success}

class TestNodesGetDetails extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodesGetDetails" + "/" + "a1" + ":" + "a1pw"))
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT: (String, String) = ("Content-Type","application/json")
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodesGetDetails" + "/" + "n1" + ":" + "n1pw"))
  // private val ORGID = "TestNodesGetDetails"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodesGetDetails" + "/" + "u1" + ":" + "u1pw"))
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp
  
  // Test data.
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodesGetDetails",
                password     = Option(Password.fastHash("u1pw")),
                username     = "u1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodesGetDetails",
                password     = Option(Password.fastHash("u2pw")),
                username     = "u2"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestNodesGetDetails2",
                password     = None,
                username     = "u3"))
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestNodesGetDetails/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestNodesGetDetails",
             owner         = TESTUSERS(0).user,
             publicKey     = "",
             token         = "$2a$10$XAPxetRTktteNbKoPXdGO.vL8LKWMp0BiiVMpTCG1ZhBMUj09/iyG") // TestNodesGetDetails/a2:a1pw
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch = "amd64",
                id = "TestNodesGetDetails/n1",
                heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
                lastHeartbeat = Option(ApiTime.nowUTC),
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "messageEndpoint",
                name = "rpin1-normal",
                nodeType = "device",
                orgid = "TestNodesGetDetails",
                owner = TESTUSERS(0).user,
                pattern = "TestNodesGetDetails/p1",
                publicKey = "key",
                regServices = """[{"url":"NodesSuiteTests/horizon.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/horizon.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
                softwareVersions = """{"horizon":"3.2.1"}""",
                token = "$2a$10$iXtbvxfSH8iN3LxPDlntEO7yLq6Wk4YhE4Tq4B7RtiqLfeHOaBE8q", // TestNodesGetDetails/n1:n1pw
                userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"horizon.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]""",
                clusterNamespace = Option("namespace0"),
                isNamespaceScoped = true),
                
        NodeRow(arch = "x86",
                id = "TestNodesGetDetails/n2",
                heartbeatIntervals = "",
                lastHeartbeat = Option(ApiTime.nowUTC),
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "rpin2-normal-x86",
                nodeType = "cluster",
                orgid = "TestNodesGetDetails",
                owner = TESTUSERS(1).user,
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "$2a$10$0EOlHl1mb2THvz3f/AnyWOV6ivUMItcQKLTzltNLmrdiLn.VCgavy",
                userInput = "",
                clusterNamespace = None,
                isNamespaceScoped = false),
        NodeRow(arch = "",
                id = "TestNodesGetDetails/n3",
                heartbeatIntervals = "",
                lastHeartbeat = None,
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "",
                nodeType = "",
                orgid = "TestNodesGetDetails",
                owner = TESTUSERS(0).user,
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "",
                userInput = "",
                clusterNamespace = Option("")),
        NodeRow(arch = "",
                id = "TestNodesGetDetails2/n4",
                heartbeatIntervals = "",
                lastHeartbeat = None,
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "",
                nodeType = "",
                orgid = "TestNodesGetDetails2",
                owner = TESTUSERS(2).user,
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "",
                userInput = "",
                clusterNamespace = None))
  private val TESTNODEERRORS: Seq[NodeErrorRow] =
    Seq(NodeErrorRow(errors = """[{"record_id":"1","workload":{"url":"myservice"},"timestamp":"yesterday","hidden":false,"message":"test error 1","event_code":"500"},{"record_id":"2","workload":{"url":"myservice2"},"timestamp":"yesterday","hidden":true,"message":"test error 2","event_code":"404"}]""",
                     lastUpdated = ApiTime.nowUTC,
                     nodeId = "TestNodesGetDetails/n1"),
        NodeErrorRow(errors = "",
                     lastUpdated = ApiTime.nowUTC,
                     nodeId = "TestNodesGetDetails/n2"))
  private val TESTNODEPOLICIES: Seq[NodePolicyRow] =
    Seq(NodePolicyRow(constraints = """["a===b"]""",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestNodesGetDetails/n1",
                      label = "node policy label",
                      description = "node policy description",
                      properties = """[{"name":"purpose","type":"list of strings","value":"testing"}]""",
                      deployment = "",   //todo: add real values
                      management = "",
                      nodePolicyVersion = ""
                      ),
        NodePolicyRow(constraints = "",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestNodesGetDetails/n2",
                      label = "node policy label",
                      description = "node policy description",
                      properties = "",
                      deployment = "",
                      management = "",
                      nodePolicyVersion = ""
                      ))
  private val TESTNODESTATUSES: Seq[NodeStatusRow] =
    Seq(NodeStatusRow(connectivity = """{"images.horizon":true}""",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestNodesGetDetails/n1",
                      runningServices = """|NodesSuiteTests/testService_0.0.1_arm|""",
                      services = """[{"agreementId":"agreementid","serviceUrl":"testService","orgid":"NodesSuiteTests","version":"0.0.1","arch":"arm","containerStatus":[]}]"""),
        NodeStatusRow(connectivity = "",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestNodesGetDetails/n2",
                      runningServices = "",
                      services = ""))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestNodesGetDetails",
               orgType            = "",
               tags               = None,
               limits             = ""),
        OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestNodesGetDetails2",
               orgType            = "",
               tags               = None,
               limits             = ""))
  
  
  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeErrorTQ ++= TESTNODEERRORS) andThen
                                       (NodePolicyTQ ++= TESTNODEPOLICIES) andThen
                                       (NodeStatusTQ ++= TESTNODESTATUSES)), AWAITDURATION)
  }
  
  // Teardown test harness.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodesGetDetails").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodesGetDetails").delete), AWAITDURATION)
  }
  
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Org") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 3)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(1).id &&
           NODES(2).id === TESTNODES(2).id)
    
    assert(NODES(0).arch                  === Option(TESTNODES(0).arch))
    assert(NODES(0).clusterNamespace      === TESTNODES(0).clusterNamespace)
    assert(NODES(0).connectivity          === Option(parse(TESTNODESTATUSES(0).connectivity).extract[Map[String, Boolean]]))
    assert(NODES(0).constraints           === Option(parse(TESTNODEPOLICIES(0).constraints).extract[List[String]]))
    assert(NODES(0).errors                === Option(parse(TESTNODEERRORS(0).errors).extract[List[Any]]))
    assert(NODES(0).heartbeatIntervals    === Option(parse(TESTNODES(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]))
    assert(NODES(0).isNamespaceScoped     === TESTNODES(0).isNamespaceScoped)
    assert(NODES(0).lastHeartbeat         === TESTNODES(0).lastHeartbeat)
    assert(NODES(0).lastUpdatedNode       === TESTNODES(0).lastUpdated)
    assert(NODES(0).lastUpdatedNodeError  === Option(TESTNODEERRORS(0).lastUpdated))
    assert(NODES(0).lastUpdatedNodePolicy === Option(TESTNODEPOLICIES(0).lastUpdated))
    assert(NODES(0).lastUpdatedNodeStatus === Option(TESTNODESTATUSES(0).lastUpdated))
    assert(NODES(0).msgEndPoint           === Option(TESTNODES(0).msgEndPoint))
    assert(NODES(0).name                  === Option(TESTNODES(0).name))
    assert(NODES(0).nodeType              === TESTNODES(0).nodeType)
    assert(NODES(0).orgid                 === TESTNODES(0).orgid)
    assert(NODES(0).owner                 === TESTUSERS(0).organization + "/" + TESTUSERS(0).username)
    assert(NODES(0).pattern               === Option(TESTNODES(0).pattern))
    assert(NODES(0).properties            === Option(parse(TESTNODEPOLICIES(0).properties).extract[List[OneProperty]]))
    assert(NODES(0).publicKey             === Option(TESTNODES(0).publicKey))
    assert(NODES(0).registeredServices    === Option(parse(TESTNODES(0).regServices).extract[List[RegService]]))
    assert(NODES(0).runningServices       === Option(TESTNODESTATUSES(0).runningServices))
    assert(NODES(0).services              === Option(parse(TESTNODESTATUSES(0).services).extract[List[OneService]]))
    assert(NODES(0).softwareVersions      === Option(parse(TESTNODES(0).softwareVersions).extract[Map[String, String]]))
    assert(NODES(0).token                 === StrConstants.hiddenPw)
    assert(NODES(0).userInput             === Option(parse(TESTNODES(0).userInput).extract[List[OneUserInputService]]))
    
    assert(NODES(1).errors                === None)
    assert(NODES(1).clusterNamespace      === TESTNODES(1).clusterNamespace)
    assert(NODES(1).connectivity          === None)
    assert(NODES(1).constraints           === None)
    assert(NODES(1).isNamespaceScoped     === TESTNODES(1).isNamespaceScoped)
    assert(NODES(1).lastUpdatedNodeError  === Option(TESTNODEERRORS(1).lastUpdated))
    assert(NODES(1).lastUpdatedNodePolicy === Option(TESTNODEPOLICIES(1).lastUpdated))
    assert(NODES(1).lastUpdatedNodeStatus === Option(TESTNODESTATUSES(1).lastUpdated))
    assert(NODES(1).properties            === None)
    assert(NODES(1).runningServices       === None)
    assert(NODES(1).services              === None)
    
    assert(NODES(2).arch                  === None)
    assert(NODES(2).clusterNamespace      === TESTNODES(2).clusterNamespace)
    assert(NODES(2).connectivity          === None)
    assert(NODES(2).constraints           === None)
    assert(NODES(2).errors                === None)
    assert(NODES(2).heartbeatIntervals    === Option(NodeHeartbeatIntervals(0, 0, 0)))
    assert(NODES(2).isNamespaceScoped     === false)
    assert(NODES(2).lastHeartbeat         === None)
    assert(NODES(2).lastUpdatedNodeError  === None)
    assert(NODES(2).lastUpdatedNodePolicy === None)
    assert(NODES(2).lastUpdatedNodeStatus === None)
    assert(NODES(2).msgEndPoint           === None)
    assert(NODES(2).name                  === None)
    assert(NODES(2).nodeType              === "device")
    assert(NODES(2).pattern               === None)
    assert(NODES(2).properties            === None)
    assert(NODES(2).publicKey             === None)
    assert(NODES(2).registeredServices    === None)
    assert(NODES(2).runningServices       === None)
    assert(NODES(2).services              === None)
    assert(NODES(2).softwareVersions      === None)
    assert(NODES(2).token                 === StrConstants.hiddenPw)
    assert(NODES(2).userInput             === None)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Architecture") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("arch", "%64").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(0).id)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By ID") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("id", "%2").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 1)
    assert(NODES(0).id                    === TESTNODES(1).id)
    assert(NODES(0).lastUpdatedNodeError  === Option(TESTNODEERRORS(1).lastUpdated))
    assert(NODES(0).lastUpdatedNodePolicy === Option(TESTNODEPOLICIES(1).lastUpdated))
    assert(NODES(0).lastUpdatedNodeStatus === Option(TESTNODESTATUSES(1).lastUpdated))
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Name") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("name", "%").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 3)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(1).id &&
           NODES(2).id === TESTNODES(2).id)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Type") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("type", "device").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 2)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(2).id)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Owner - Agbot") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("owner", "%u2").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(1).id)
  
    assert(NODES(0).token === StrConstants.hiddenPw)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Owner - Root") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").param("owner", "%u2").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(1).id)
  }
  
  test("GET /orgs/" + "TestNodesGetDetails" + "/node-details -- Filter By Owner - User1") {
    val response: HttpResponse[String] = Http(URL + "TestNodesGetDetails" + "/node-details").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeDetails] = parse(response.body).extract[List[NodeDetails]]
    assert(NODES.size === 2)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(2).id)
    
    assert(NODES(0).token === StrConstants.hiddenPw)
    assert(NODES(1).token === StrConstants.hiddenPw)
  }
}
