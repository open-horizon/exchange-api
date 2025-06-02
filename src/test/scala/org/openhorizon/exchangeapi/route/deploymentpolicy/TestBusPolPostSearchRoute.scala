package org.openhorizon.exchangeapi.route.deploymentpolicy

import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.deploymentpolicy.{BusinessPolicyNodeResponse, PostBusinessPolicySearchRequest, PostBusinessPolicySearchResponse}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.{SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BusinessPoliciesTQ, BusinessPolicyRow}
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreementRow, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.service.{ServiceRow, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

// Allows route to be tested without any other route dependencies.
class TestBusPolPostSearchRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/a1" + ":" + "a1tok"))
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/u1" + ":" + "u1pw"))
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  
  private val AWAITDURATION: Duration = 15.seconds
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp
  
  // Resources we minimally and statically need for all test cases.
  private val TESTUSER: UserRow =
    UserRow(createdAt    = TIMESTAMP,
            isHubAdmin   = false,
            isOrgAdmin   = false,
            modifiedAt   = TIMESTAMP,
            organization = "TestPolicySearchPost",
            password     = None,
            username     = "u1")
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestPolicySearchPost/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestPolicySearchPost",
             owner         = TESTUSER.user,
             publicKey     = "",
             token         = Password.hash("a1tok")) // "TestPolicySearchPost/a1:a1tok"
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestPolicySearchPost",
           orgType            = "",
           tags               = None,
           limits             = "")
  private val TESTPOLICIES: Seq[BusinessPolicyRow] =
    Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                          constraints    = """["a == b"]""",
                          created        = ApiTime.nowUTC,
                          description    = "",
                          label          = "pol1",
                          lastUpdated    = ApiTime.nowUTC,
                          orgid          = "TestPolicySearchPost",
                          owner          = TESTUSER.user,
                          properties     = """[{"name":"purpose","value":"location"}]""",
                          service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"arm","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800},"clusterNamespace": null}""",
                          userInput      = "",
                          secretBinding  = ""))
  private val TESTSERVICES: Seq[ServiceRow] =
    Seq(ServiceRow(arch                       = "arm",
                   clusterDeployment          = "",
                   clusterDeploymentSignature = "",
                   deployment                 = "",
                   deploymentSignature        = "",
                   description                = "",
                   documentation              = "",
                   imageStore                 = "",
                   label                      = "",
                   lastUpdated                = ApiTime.nowUTC,
                   matchHardware              = "",
                   orgid                      = "TestPolicySearchPost",
                   owner                      = TESTUSER.user,
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestPolicySearchPost/svc1_1.0.0_arm",
                   sharable                   = "multiple",
                   url                        = "svc1",
                   userInput                  = "",
                   version                    = "1.0.0"))
  
  
  implicit private val formats: Formats = DefaultFormats.withLong
  
  // Begin building testing harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ += TESTORGANIZATION) andThen
                                       (UsersTQ += TESTUSER) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (ServicesTQ ++= TESTSERVICES)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPolicySearchPost").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPolicySearchPost").delete), AWAITDURATION)
  }
  
  // Isolates test cases.
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(SearchOffsetPolicyTQ.dropAllOffsets()), AWAITDURATION)
  }
  
  // Node Agreements that are dynamically needed, specific to the test case.
  def fixtureNodeAgreements(testCode: Seq[NodeAgreementRow] => Any, testData: Seq[NodeAgreementRow]): Any = {
    // Create resources and continue.
    try {
      Await.result(DBCONNECTION.run(NodeAgreementsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    // Teardown created resources.
    finally
      Await.result(DBCONNECTION.run(NodeAgreementsTQ.filter(_.agId inSet testData.map(_.agId)).delete), AWAITDURATION)
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Organizations that are dynamically needed, specific to the test case.
  def fixtureOrganizations(testCode: Seq[OrgRow] => Any, testData: Seq[OrgRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(OrgsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(OrgsTQ.filter(_.orgid inSet testData.map(_.orgId)).delete), AWAITDURATION)
  }
  
  // Offsets/Sessions that are dynamically needed, specific to the test case.
  def fixturePagination(testCode: Seq[SearchOffsetPolicyAttributes] => Any, testData: Seq[SearchOffsetPolicyAttributes]): Any = {
    try{
      Await.result(DBCONNECTION.run(SearchOffsetPolicyTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      None
  }
  
  // Policies that are dynamically needed, specific to the test case.
  def fixturePolicies(testCode: Seq[BusinessPolicyRow] => Any, testData: Seq[BusinessPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(BusinessPoliciesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(BusinessPoliciesTQ.filter(_.businessPolicy inSet testData.map(_.businessPolicy)).delete), AWAITDURATION)
  }
  
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 400 Bad Request - changeSince < 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(-1L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 400 Bad Request - numEntries < 0") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(-1), Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- No Nodes") {
    fixturePolicies(
      _ => {
        val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.NOT_FOUND.intValue)
    
        val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
        assert(responseBody.nodes.isEmpty)
        assert(responseBody.offsetUpdated === false)
    
        //val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
        //assert(offset.nonEmpty)
        //assert(offset.head._1.isEmpty)
        //assert(offset.head._2 === 0L)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- 409 Bad Session - Agbot Session Desynchronization") {
    val TESTOFFSET: Seq[SearchOffsetPolicyAttributes] =
      Seq(SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
                                       offset = Some(ApiTime.nowUTC),
                                       policy = "TestPolicySearchPost/pol1",
                                       session = Some("token2")))
    
    fixturePolicies(
      _ => {
        fixturePagination(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("body: " + response.body)
            assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
    
            val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            assert(responseBody.nodes.isEmpty)
            assert(responseBody.offsetUpdated === false)
    
            val offset: Seq[(Option[String], Option[String])] = Await.result(DBCONNECTION.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
            assert(offset.nonEmpty)
            assert(offset.head._1 === TESTOFFSET.head.offset)
            assert(offset.head._2 === TESTOFFSET.head.session)
          }, TESTOFFSET)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched Service Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes.head.id === "TestPolicySearchPost/n1")
            assert(nodes.head.nodeType === "device")
            assert(nodes.head.publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Matched Service Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched ALL Service Architectures - *") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTPOLICY: Seq[BusinessPolicyRow] =
      Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                            constraints    = """["a == b"]""",
                            created        = ApiTime.nowUTC,
                            description    = "",
                            label          = "pol1",
                            lastUpdated    = ApiTime.nowUTC,
                            orgid          = "TestPolicySearchPost",
                            owner          = TESTUSER.user,
                            properties     = """[{"name":"purpose","value":"location"}]""",
                            service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"*","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800},"clusterNamespace": null}""",
                            userInput      = "",
                            secretBinding  = ""))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes.head.id === "TestPolicySearchPost/n1")
            assert(nodes.head.nodeType === "device")
            assert(nodes.head.publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICY)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched ALL Service Architectures - Unspecified Architecture") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTPOLICY: Seq[BusinessPolicyRow] =
      Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                            constraints    = """["a == b"]""",
                            created        = ApiTime.nowUTC,
                            description    = "",
                            label          = "pol1",
                            lastUpdated    = ApiTime.nowUTC,
                            orgid          = "TestPolicySearchPost",
                            owner          = TESTUSER.user,
                            properties     = """[{"name":"purpose","value":"location"}]""",
                            service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800},"clusterNamespace": null}""",
                            userInput      = "",
                            secretBinding  = ""))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val nodes: Seq[BusinessPolicyNodeResponse] = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes
            assert(nodes.length === 1)
            assert(nodes.head.id === "TestPolicySearchPost/n1")
            assert(nodes.head.nodeType === "device")
            assert(nodes.head.publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICY)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Node Type") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            
            val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
            assert(NODE.id === "TestPolicySearchPost/n1")
            assert(NODE.nodeType === "device")
            assert(NODE.publicKey === "key")
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Public Key") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Heartbeat") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "pattern",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = None,
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.NOT_FOUND.intValue)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Stale") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(_ => {
      fixtureNodes(_ => {
        val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(ApiTime.nowSeconds, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.NOT_FOUND.intValue)
        
        val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
        assert(responseBody.nodes.isEmpty)
        assert(responseBody.offsetUpdated === false)
      }, TESTNODE)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - No Matched Policy Organization") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost2/n1",
                  orgid = "TestPolicySearchPost2",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTORGANIZATION: Seq[OrgRow] =
      Seq(OrgRow(description = "",
                 heartbeatIntervals = "",
                 label = "",
                 lastUpdated = "",
                 orgId = "TestPolicySearchPost2",
                 orgType = "",
                 tags = None,
                 limits = ""))
    
    fixtureOrganizations(
      _ => {
        fixturePolicies(
          _ => {
            fixtureNodes(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.NOT_FOUND.intValue)
              }, TESTNODE)
          }, TESTPOLICIES)
      }, TESTORGANIZATION)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node - Matched Policy Organization - With Request") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost2/n1",
                  orgid = "TestPolicySearchPost2",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTORGANIZATION: Seq[OrgRow] =
      Seq(OrgRow(description = "",
                 heartbeatIntervals = "",
                 label = "",
                 lastUpdated = "",
                 orgId = "TestPolicySearchPost2",
                 orgType = "",
                 tags = None,
                 limits = ""))
    
    fixtureOrganizations(
      _ => {
        fixturePolicies(
          _ => {
            fixtureNodes(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, Some(List("TestPolicySearchPost2")), None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost2/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODE)
          }, TESTPOLICIES)
      }, TESTORGANIZATION)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - Matched Node") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "TestPolicySearchPost/svc1",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            fixtureNodeAgreements(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.NOT_FOUND.intValue)
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - Mismatched Node") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n2",
                orgid = "TestPolicySearchPost",
                token = "",
                name = "",
                owner = TESTUSER.user,
                nodeType = "device",
                pattern = "",
                regServices = "[]",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "key",
                arch = "arm",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "TestPolicySearchPost/svc1",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            fixtureNodeAgreements(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                assert(parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.length === 1)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n2")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - Mismatched Service URL") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "url",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            fixtureNodeAgreements(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - No Serivce URL") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "",
                           state = "signed",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            fixtureNodeAgreements(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
  
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Node Agreement - No Matched Node - No Agreement State") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC))
    val TESTNODEAGREEMENT: Seq[NodeAgreementRow] =
      Seq(NodeAgreementRow(agId = "ag1",
                           nodeId = "TestPolicySearchPost/n1",
                           services = "",
                           agrSvcOrgid = "",
                           agrSvcPattern = "",
                           agrSvcUrl = "TestPolicySearchPost/svc1",
                           state = "",
                           lastUpdated = ApiTime.nowUTC))
    
    fixturePolicies(
      _ => {
        fixtureNodes(
          _ => {
            fixtureNodeAgreements(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("Code: " + response.code)
                info("Body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val NODE: BusinessPolicyNodeResponse = parse(response.body).extract[PostBusinessPolicySearchResponse].nodes.head
                assert(NODE.id === "TestPolicySearchPost/n1")
                assert(NODE.nodeType === "device")
                assert(NODE.publicKey === "key")
              }, TESTNODEAGREEMENT)
          }, TESTNODE)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Pagination - Increment Offset") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n2",
                orgid = "TestPolicySearchPost",
                token = "",
                name = "",
                owner = TESTUSER.user,
                nodeType = "device",
                pattern = "",
                regServices = "[]",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "key",
                arch = "arm",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.futureUTC(60)))
    //val TESTPAGINATION: Seq[SearchOffsetPolicyAttributes] =
      //Seq(SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
        //offset = Some(ApiTime.beginningUTC),
        //policy = "TestPolicySearchPost/pol1",
        //session = Some("token")))
    
    fixturePolicies(
      _ => {
        //fixturePagination(
          //_ => {
            fixtureNodes(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(1), Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val RESPONSEBODY: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
                assert(RESPONSEBODY.nodes.length === 1)
                assert(RESPONSEBODY.offsetUpdated === true)
                assert(RESPONSEBODY.nodes.head.id === "TestPolicySearchPost/n1")
                
                val offset: Seq[(Option[String], Option[String])] = Await.result(DBCONNECTION.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
                assert(offset.nonEmpty)
                assert(offset.head._1 === Some(TESTNODE.head.lastUpdated))
                assert(offset.head._2 === Some("token"))
              }, TESTNODE)
          //}, TESTPAGINATION)
      }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search -- Pagination - Increment Offset - Prior Offset") {
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.nowUTC),
          NodeRow(id = "TestPolicySearchPost/n2",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = TESTUSER.user,
                  nodeType = "device",
                  pattern = "",
                  regServices = "[]",
                  userInput = "",
                  msgEndPoint = "",
                  softwareVersions = "",
                  lastHeartbeat = Some(ApiTime.nowUTC),
                  publicKey = "key",
                  arch = "arm",
                  heartbeatIntervals = "",
                  lastUpdated = ApiTime.futureUTC(60)))
    val TESTPAGINATION: Seq[SearchOffsetPolicyAttributes] =
      Seq(SearchOffsetPolicyAttributes(agbot = "TestPolicySearchPost/a1",
                                       offset = Some(ApiTime.beginningUTC),
                                       policy = "TestPolicySearchPost/pol1",
                                       session = Some("token")))
  
    fixturePolicies(
      _ => {
        fixturePagination(
          _ => {
            fixtureNodes(
              _ => {
                val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(1), Some("token")))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
                info("code: " + response.code)
                info("body: " + response.body)
                assert(response.code === HttpCode.POST_OK.intValue)
                
                val RESPONSEBODY: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
                assert(RESPONSEBODY.nodes.length === 1)
                assert(RESPONSEBODY.offsetUpdated === true)
                assert(RESPONSEBODY.nodes.head.id === "TestPolicySearchPost/n1")
                
                val offset: Seq[(Option[String], Option[String])] = Await.result(DBCONNECTION.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
                assert(offset.nonEmpty)
                assert(offset.head._1 === Some(TESTNODE.head.lastUpdated))
                assert(offset.head._2 === Some("token"))
              }, TESTNODE)
          }, TESTPAGINATION)
     }, TESTPOLICIES)
  }
}
