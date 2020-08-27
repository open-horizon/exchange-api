package com.horizon.exchangeapi.route.catalog

import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeErrorRow, NodeErrorTQ, NodeHeartbeatIntervals, NodeRow, NodeStatusRow, NodeStatusTQ, NodesTQ, OneService, OneUserInputService, OrgRow, OrgsTQ, RegService, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, NodeCatalog, Role, StrConstants, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable.Map
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestCatalogGetNodes extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val AGBOTAUTH = ("Authorization","Basic " + ApiUtils.encode("TestCatalogGetNodes" + "/" + "a1" + ":" + "a1pw"))
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT = ("Content-Type","application/json")
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val NODEAUTH = ("Authorization","Basic " + ApiUtils.encode("TestCatalogGetNodes" + "/" + "n1" + ":" + "n1pw"))
  // private val ORGID = "TestCatalogGetNodes"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/catalog/"
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode("TestCatalogGetNodes" + "/" + "u1" + ":" + "u1pw"))
  
  private implicit val formats = DefaultFormats
  
  // Test data.
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestCatalogGetNodes/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestCatalogGetNodes",
             owner         = "TestCatalogGetNodes/u1",
             publicKey     = "",
             token         = "$2a$10$SpL9Rzo1SdteOO/xr9282.g0ZfgxWl57YLJxNWrkdq/nR/FaC8rVG") // TestCatalogGetNodes/a2:a1pw
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch = "amd64",
                id = "TestCatalogGetNodes/n1",
                heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
                lastHeartbeat = Some(ApiTime.nowUTC),
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "messageEndpoint",
                name = "rpin1-normal",
                nodeType = "device",
                orgid = "TestCatalogGetNodes",
                owner = "TestCatalogGetNodes/u1",
                pattern = "TestCatalogGetNodes/p1",
                publicKey = "key",
                regServices = """[{"url":"NodesSuiteTests/bluehorizon.network.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/bluehorizon.network.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
                softwareVersions = """{"horizon":"3.2.1"}""",
                token = "$2a$10$rFvQd.eGhaWiApRtMjv3F.6wVsfHIYmAww9r.2XozkrzBpMlb3nxO", // TestCatalogGetNodes/n1:n1pw
                userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"bluehorizon.network.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]"""),
        NodeRow(arch = "x86",
                id = "TestCatalogGetNodes/n2",
                heartbeatIntervals = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "rpin2-normal-x86",
                nodeType = "cluster",
                orgid = "TestCatalogGetNodes",
                owner = "TestCatalogGetNodes/u2",
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "$2a$10$0EOlHl1mb2THvz3f/AnyWOV6ivUMItcQKLTzltNLmrdiLn.VCgavy",
                userInput = ""),
        NodeRow(arch = "",
                id = "TestCatalogGetNodes/n3",
                heartbeatIntervals = "",
                lastHeartbeat = None,
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "",
                nodeType = "",
                orgid = "TestCatalogGetNodes",
                owner = "TestCatalogGetNodes/u1",
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "",
                userInput = ""),
        NodeRow(arch = "",
                id = "TestCatalogGetNodes2/n4",
                heartbeatIntervals = "",
                lastHeartbeat = None,
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "",
                nodeType = "",
                orgid = "TestCatalogGetNodes2",
                owner = "TestCatalogGetNodes2/u3",
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "",
                userInput = ""))
  private val TESTNODEERRORS: Seq[NodeErrorRow] =
    Seq(NodeErrorRow(errors = """[{"record_id":"1","workload":{"url":"myservice"},"timestamp":"yesterday","hidden":false,"message":"test error 1","event_code":"500"},{"record_id":"2","workload":{"url":"myservice2"},"timestamp":"yesterday","hidden":true,"message":"test error 2","event_code":"404"}]""",
                     lastUpdated = ApiTime.nowUTC,
                     nodeId = "TestCatalogGetNodes/n1"),
        NodeErrorRow(errors = "",
                     lastUpdated = ApiTime.nowUTC,
                     nodeId = "TestCatalogGetNodes/n2"))
  private val TESTNODESTATUSES: Seq[NodeStatusRow] =
    Seq(NodeStatusRow(connectivity = """{"images.bluehorizon.network":true}""",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestCatalogGetNodes/n1",
                      runningServices = """|NodesSuiteTests/testService_0.0.1_arm|""",
                      services = """[{"agreementId":"agreementid","serviceUrl":"testService","orgid":"NodesSuiteTests","version":"0.0.1","arch":"arm","containerStatus":[]}]"""),
        NodeStatusRow(connectivity = "",
                      lastUpdated = ApiTime.nowUTC,
                      nodeId = "TestCatalogGetNodes/n2",
                      runningServices = "",
                      services = ""))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestCatalogGetNodes",
               orgType            = "",
               tags               = None),
        OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               orgId              = "TestCatalogGetNodes2",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "",
                hashedPw    = "$2a$10$k5eecUM77Zh0EfbEjZFYJ.qqUIlxgxKh/HIrEaO1kmZHM5VrtYwcS", // TestCatalogGetNodes/u1:u1pw
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestCatalogGetNodes",
                updatedBy   = "",
                username    = "TestCatalogGetNodes/u1"),
        UserRow(admin       = false,
                email       = "",
                hashedPw    = "$2a$10$0EOlHl1mb2THvz3f/AnyWOV6ivUMItcQKLTzltNLmrdiLn.VCgavy",
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestCatalogGetNodes",
                updatedBy   = "",
                username    = "TestCatalogGetNodes/u2"),
        UserRow(admin       = false,
                email       = "",
                hashedPw    = "",
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestCatalogGetNodes2",
                updatedBy   = "",
                username    = "TestCatalogGetNodes2/u3"))
  
  // Build test harness.
  override def beforeAll {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ.rows ++= TESTUSERS) andThen
                                       (AgbotsTQ.rows += TESTAGBOT) andThen
                                       (NodesTQ.rows ++= TESTNODES) andThen
                                       (NodeErrorTQ.rows ++= TESTNODEERRORS) andThen
                                       (NodeStatusTQ.rows ++= TESTNODESTATUSES)), AWAITDURATION)
  }
  
  // Teardown test harness.
  override def afterAll {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "TestCatalogGetNodes").delete andThen
                                       OrgsTQ.rows.filter(_.orgid startsWith "TestCatalogGetNodes").delete), AWAITDURATION)
  
    DBCONNECTION.getDb.close()
  }
  
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Org") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    //info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 3)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(1).id &&
           NODES(2).id === TESTNODES(2).id)
    
    assert(NODES(0).arch                  === Some(TESTNODES(0).arch))
    assert(NODES(0).connectivity          === Some(parse(TESTNODESTATUSES(0).connectivity).extract[Map[String, Boolean]]))
    assert(NODES(0).errors                === Some(parse(TESTNODEERRORS(0).errors).extract[List[Any]]))
    assert(NODES(0).heartbeatIntervals    === Some(parse(TESTNODES(0).heartbeatIntervals).extract[NodeHeartbeatIntervals]))
    assert(NODES(0).lastHeartbeat         === TESTNODES(0).lastHeartbeat)
    assert(NODES(0).lastUpdatedNode       === TESTNODES(0).lastUpdated)
    assert(NODES(0).lastUpdatedNodeError  === Some(TESTNODEERRORS(0).lastUpdated))
    assert(NODES(0).lastUpdatedNodeStatus === Some(TESTNODESTATUSES(0).lastUpdated))
    assert(NODES(0).msgEndPoint           === Some(TESTNODES(0).msgEndPoint))
    assert(NODES(0).name                  === Some(TESTNODES(0).name))
    assert(NODES(0).nodeType              === TESTNODES(0).nodeType)
    assert(NODES(0).orgid                 === TESTNODES(0).orgid)
    assert(NODES(0).owner                 === TESTNODES(0).owner)
    assert(NODES(0).pattern               === Some(TESTNODES(0).pattern))
    assert(NODES(0).publicKey             === Some(TESTNODES(0).publicKey))
    assert(NODES(0).registeredServices    === Some(parse(TESTNODES(0).regServices).extract[List[RegService]]))
    assert(NODES(0).runningServices       === Some(TESTNODESTATUSES(0).runningServices))
    assert(NODES(0).services              === Some(parse(TESTNODESTATUSES(0).services).extract[List[OneService]]))
    assert(NODES(0).softwareVersions      === Some(parse(TESTNODES(0).softwareVersions).extract[Map[String, String]]))
    assert(NODES(0).token                 === StrConstants.hiddenPw)
    assert(NODES(0).userInput             === Some(parse(TESTNODES(0).userInput).extract[List[OneUserInputService]]))
    
    assert(NODES(2).errors                === None)
    assert(NODES(1).connectivity          === None)
    assert(NODES(1).lastUpdatedNodeError  === Some(TESTNODEERRORS(1).lastUpdated))
    assert(NODES(1).lastUpdatedNodeStatus === Some(TESTNODESTATUSES(1).lastUpdated))
    assert(NODES(1).runningServices       === None)
    assert(NODES(1).services              === None)
    
    assert(NODES(2).arch                  === None)
    assert(NODES(2).connectivity          === None)
    assert(NODES(2).errors                === None)
    assert(NODES(2).heartbeatIntervals    === Some(NodeHeartbeatIntervals(0, 0, 0)))
    assert(NODES(2).lastHeartbeat         === None)
    assert(NODES(2).lastUpdatedNodeError  === None)
    assert(NODES(2).lastUpdatedNodeStatus === None)
    assert(NODES(2).msgEndPoint           === None)
    assert(NODES(2).name                  === None)
    assert(NODES(2).nodeType              === "device")
    assert(NODES(2).pattern               === None)
    assert(NODES(2).publicKey             === None)
    assert(NODES(2).registeredServices    === None)
    assert(NODES(2).runningServices       === None)
    assert(NODES(2).services              === None)
    assert(NODES(2).softwareVersions      === None)
    assert(NODES(2).token                 === StrConstants.hiddenPw)
    assert(NODES(2).userInput             === None)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Architecture") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("arch", "%64").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(0).id)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By ID") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("id", "%2").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 1)
    assert(NODES(0).id                    === TESTNODES(1).id)
    assert(NODES(0).lastUpdatedNodeError === Some(TESTNODEERRORS(1).lastUpdated))
    assert(NODES(0).lastUpdatedNodeStatus === Some(TESTNODESTATUSES(1).lastUpdated))
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Name") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("name", "%").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 3)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(1).id &&
           NODES(2).id === TESTNODES(2).id)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Type") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("type", "device").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 2)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(2).id)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Owner - Agbot") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("owner", "%u2").headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(1).id)
  
    assert(NODES(0).token === TESTNODES(1).token)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Owner - Root") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").param("owner", "%u2").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 1)
    assert(NODES(0).id === TESTNODES(1).id)
  }
  
  test("GET /catalog/" + "TestCatalogGetNodes" + "/nodes -- Filter By Owner - User1") {
    val response: HttpResponse[String] = Http(URL + "TestCatalogGetNodes" + "/nodes").headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    // info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    
    val NODES: List[NodeCatalog] = parse(response.body).extract[List[NodeCatalog]]
    assert(NODES.size === 2)
    assert(NODES(0).id === TESTNODES(0).id &&
           NODES(1).id === TESTNODES(2).id)
    
    assert(NODES(0).token === TESTNODES(0).token)
    assert(NODES(1).token === StrConstants.hiddenPw)
  }
}
