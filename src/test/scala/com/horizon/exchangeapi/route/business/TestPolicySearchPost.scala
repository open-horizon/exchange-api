package com.horizon.exchangeapi.route.business

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.headers.CacheDirectives.public
import akka.stream.ActorMaterializer
import com.horizon.exchangeapi.ExchangeApiApp.{cpds, db, logger}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, BusinessPolicyNodeResponse, BusinessRoutes, ExchConfig, ExchangeApi, HttpCode, PostBusinessPolicySearchRequest, PostBusinessPolicySearchResponse, PostPutBusinessPolicyRequest, PostPutOrgRequest, PostPutServiceRequest, PostPutUsersRequest, PutAgbotsRequest, PutNodesRequest, Role}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, BService, BServiceVersions, BusinessPoliciesTQ, BusinessPolicyRow, NodeAgreementRow, NodeAgreementsTQ, NodeHeartbeatIntervals, NodeRow, NodeType, NodesTQ, OneProperty, OneUserInputService, OneUserInputValue, OrgRow, OrgsTQ, Prop, RegService, ResourceChangesTQ, SearchOffsetPolicyTQ, ServiceRow, ServicesTQ, UserRow, UsersTQ}
import com.mchange.v2.c3p0.ComboPooledDataSource
import org.json4s.jackson.JsonMethods.parse
import org.json4s.{DefaultFormats, Formats, JValue, JsonInput, jvalue2extractable, string2JsonInput}
import org.json4s.native.Serialization.write
import org.junit.runner.RunWith
import org.scalactic.Bad
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable
import scala.collection.immutable.Map
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.parsing.json

class TestDBConnection {
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.getHostAndPort match {
    case (h, p) => ExchangeApi.serviceHost = h;
      ExchangeApi.servicePort = p
  }
  
  private var cpds: ComboPooledDataSource = new ComboPooledDataSource
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setUser(ExchConfig.getString("api.db.user"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  // the settings below are optional -- c3p0 can work with defaults
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  
  private val maxConns: Int = ExchConfig.getInt("api.db.maxPoolSize")
  
  private val db: Database =
    if (cpds != null) {
      Database.forDataSource(cpds,
                             Some(maxConns),
                             AsyncExecutor("ExchangeExecutor", maxConns, maxConns, 1000, maxConns))
  }
  else
    null
  
  def getDb: Database = db
}


class TestPolicySearchPost extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/a1" + ":" + "a1tok"))
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestPolicySearchPost/u1" + ":" + "u1pw"))
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  
  private val AWAITDURATION: Duration = 15.seconds
  
  private val OLDTESTNODES: Seq[NodeRow] =
    Seq(NodeRow(id = "TestPolicySearchPost2/n1",
                orgid = "TestPolicySearchPost2",
                token = "$2a$10$snRHtgl95HpYnPZIZJyElOeyOzHiSIzmkd/GtW4ju6jaCUUuTwFly",
                name = "rpin1-norm",
                owner = "TestPolicySearchPost2/u1",
                nodeType = "device",
                pattern = "TestPolicySearchPost/p1",
                regServices = "[]",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "NODEABC",
                arch = "",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n1",
                orgid = "TestPolicySearchPost",
                token = "$2a$10$tI.AIySD7.GfuL0Kme7uTu6JOB6gcg0wQ4zFT1DBnPCQchrfVbv1S",
                name = "rpin1-normal",
                owner = "TestPolicySearchPost/u1",
                nodeType = "device",
                pattern = "",
                regServices = """[{"url":"TestPolicySearchPost/bluehorizon.network.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"TestPolicySearchPost/bluehorizon.network.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
                userInput = """[{"serviceOrgid":"TestPolicySearchPost","serviceUrl":"bluehorizon.network.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":7},{"name":"UI_BOOLEAN","value":true}]}]""",
                msgEndPoint = "",
                softwareVersions = """{"horizon":"3.2.1"}""",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "NODEABC",
                arch = "amd64",
                heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
                lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n2",
                orgid = "TestPolicySearchPost",
                token = "$2a$10$0A697FLYYm1XEuLjl16FsOF1shxc95JLNZVg.1TVP/.ns7LJaVRNS",
                name = "rpin2-mem-400-vers-2",
                owner = "TestPolicySearchPost/u1",
                nodeType = "cluster",
                pattern = "",
                regServices = """[{"url":"TestPolicySearchPost/bluehorizon.network.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n2 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"400","propType":"int","op":">="},{"name":"version","value":"2.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]}]""",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "NODEABC",
                arch = "amd64",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n3",
                orgid = "TestPolicySearchPost",
                token = "$2a$10$rZqlt/EqOY1Oc7nX8cxYwueIbzi5q7aOhqbOTbXrftkWvXm.yCDo6",
                name = "rpin3-netspeed-amd64",
                owner = "TestPolicySearchPost/u1",
                nodeType = "device",
                pattern = "",
                regServices = """[{"url":"TestPolicySearchPost/bluehorizon.network.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n3 netspeed}","properties":[{"name":"arch","value":"amd64","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]}]""",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "NODEABC",
                arch = "amd64",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC),
        NodeRow(id = "TestPolicySearchPost/n4",
                orgid = "TestPolicySearchPost",
                token = "$2a$10$760FrBzUzBeUNAS.TMVFlO9o1o71luZjtD3MsjW8O/th8QMK/Bt8y",
                name = "rpin4-bad-url",
                owner = "TestPolicySearchPost/u1",
                nodeType = "device",
                pattern = "",
                regServices = """[{"url":"TestPolicySearchPost/bluehorizon.network.notthere","numAgreements":1,"configState":"active","policy":"{json policy for n4 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"400","propType":"int","op":">="},{"name":"version","value":"2.0.0","propType":"version","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]}]""",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Some(ApiTime.nowUTC),
                publicKey = "NODEABC",
                arch = "arm",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC))
  private val OLDTESTNODEAGREEMENTS: Seq[NodeAgreementRow] =
    Seq(NodeAgreementRow(agId = "testagreementn4",
        nodeId = "TestPolicySearchPost/n4",
        services = """[{"orgid":"TestPolicySearchPost","url":"bluehorizon.network.sdr"}]""",
        agrSvcOrgid = "",
        agrSvcPattern = "",
        agrSvcUrl = "",
        state = "signed",
        lastUpdated = ApiTime.nowUTC))
  private val OLDTESTNODEAGREEMENTS2: Seq[NodeAgreementRow] =
    OLDTESTNODEAGREEMENTS ++ Seq(NodeAgreementRow(agId = "agr1",
                                                  nodeId = "TestPolicySearchPost/n1",
                                                  services = """[{"orgid":"TestPolicySearchPost","url":"bluehorizon.network.sdr"}]""",
                                                  agrSvcOrgid = "TestPolicySearchPost",
                                                  agrSvcPattern = "p1",
                                                  agrSvcUrl = "TestPolicySearchPost/bluehorizon.network.sdr",
                                                  state = "negotiating",
                                                  lastUpdated = ApiTime.nowUTC),
                                 NodeAgreementRow(agId = "agr2",
                                                  nodeId = "TestPolicySearchPost2/n1",
                                                  services = """[{"orgid":"TestPolicySearchPost","url":"bluehorizon.network.sdr"}]""",
                                                  agrSvcOrgid = "TestPolicySearchPost",
                                                  agrSvcPattern = "p1",
                                                  agrSvcUrl = "TestPolicySearchPost/bluehorizon.network.sdr",
                                                  state = "signed",
                                                  lastUpdated = ApiTime.nowUTC))
  private val OLDTESTNODEAGREEMENTS3: Seq[NodeAgreementRow] =
    OLDTESTNODEAGREEMENTS2 ++ Seq(NodeAgreementRow(agId = "9951",
                                  nodeId = "TestPolicySearchPost/n1",
                                  services = """[{"orgid":"NodesSuiteTests","url":"pws"}]""",
                                  agrSvcOrgid = "TestPolicySearchPost",
                                  agrSvcPattern = "p1",
                                  agrSvcUrl = "pws",
                                  state = "signed",
                                  lastUpdated = ApiTime.nowUTC))
  private val OLDTESTNODEAGREEMENTS4: Seq[NodeAgreementRow] =
    OLDTESTNODEAGREEMENTS3.filterNot(_.agId === "agr1") ++ Seq(NodeAgreementRow(agId = "agr1",
                                                               nodeId = "TestPolicySearchPost/n1",
                                                               services = """[{"orgid":"NodesSuiteTests","url":"bluehorizon.network.netspeed"}]""",
                                                               agrSvcOrgid = "TestPolicySearchPost",
                                                               agrSvcPattern = "p1",
                                                               agrSvcUrl = "TestPolicySearchPost/bluehorizon.network.netspeed",
                                                               state = "signed",
                                                               lastUpdated = ApiTime.nowUTC))
  private val OLDTESTPOLICIES: Seq[BusinessPolicyRow] =
    Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/mybuspolnetspeed",
                          orgid = "TestPolicySearchPost",
                          owner = "TestPolicySearchPost/u1",
                          label = "mybuspolnetspeed",
                          description = "desc",
                          service = """{"name":"bluehorizon.network.netspeed","org":"TestPolicySearchPost","arch":"amd64","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                          userInput = "",
                          properties = """[{"name":"purpose","value":"location"}]""",
                          constraints = """["a == b"]""",
                          lastUpdated = ApiTime.nowUTC,
                          created = ApiTime.nowUTC),
        BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/mybuspolsdr",
                          orgid = "TestPolicySearchPost",
                          owner = "TestPolicySearchPost/u1",
                          label = "mybuspolsdr",
                          description = "desc",
                          service = """{"name":"bluehorizon.network.sdr","org":"TestPolicySearchPost","arch":"*","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                          userInput = "",
                          properties = """[{"name":"purpose","value":"location"}]""",
                          constraints = """["a == b"]""",
                          lastUpdated = ApiTime.nowUTC,
                          created = ApiTime.nowUTC))
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestPolicySearchPost/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestPolicySearchPost",
             owner         = "TestPolicySearchPost/u1",
             publicKey     = "",
             token         = "$2a$10$2ElhDrDUXcFzvU63Gl3dWeGYsWYTqgaBxkthhhdwwWc2YTP1yB4Ky")
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestPolicySearchPost",
           orgType            = "",
           tags               = None)
  private val TESTPOLICIES: Seq[BusinessPolicyRow] =
    Seq(BusinessPolicyRow(businessPolicy = "TestPolicySearchPost/pol1",
                          constraints    = """["a == b"]""",
                          created        = ApiTime.nowUTC,
                          description    = "",
                          label          = "pol1",
                          lastUpdated    = ApiTime.nowUTC,
                          orgid          = "TestPolicySearchPost",
                          owner          = "TestPolicySearchPost/u1",
                          properties     = """[{"name":"purpose","value":"location"}]""",
                          service        = """{"name":"svc1","org":"TestPolicySearchPost","arch":"arm","serviceVersions":[{"version":"1.0.0"}],"nodeHealth":{"missing_heartbeat_interval":1800,"check_agreement_status":1800}}""",
                          userInput      = ""))
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
                   owner                      = "TestPolicySearchPost/u1",
                   public                     = false,
                   requiredServices           = "",
                   service                    = "TestPolicySearchPost/svc1_1.0.0_arm",
                   sharable                   = "multiple",
                   url                        = "svc1",
                   userInput                  = "",
                   version                    = "1.0.0"))
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
            email       = "",
            hashedPw    = "",
            lastUpdated = ApiTime.nowUTC,
            orgid       = "TestPolicySearchPost",
            updatedBy   = "",
            username    = "TestPolicySearchPost/u1")
  
  implicit private val formats: Formats = DefaultFormats.withLong
  
  
  override def beforeAll() {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows += TESTORGANIZATION) andThen
                                       (UsersTQ.rows += TESTUSER) andThen
                                       (AgbotsTQ.rows += TESTAGBOT) andThen
                                       (ServicesTQ.rows ++= TESTSERVICES)), AWAITDURATION)
    
    Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost2").postData(write(PostPutOrgRequest(None, "TestPolicySearchPost2", "desc2", None, None))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    Http(sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestPolicySearchPost2" + "/users/" + "u1").postData(write(PostPutUsersRequest("u2pw", admin = false, "u1" + "@hotmail.com"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "TestPolicySearchPost").delete andThen
                                       OrgsTQ.rows.filter(_.orgid startsWith "TestPolicySearchPost").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.dropAllOffsets()), AWAITDURATION)
  }
  
  
  def fixtureNodeAgreements(testCode: Seq[NodeAgreementRow] ⇒ Any, testData: Seq[NodeAgreementRow]): Any = {
    val testNodeAgreements: Seq[NodeAgreementRow] = testData
  
    try {
      Await.result(DBCONNECTION.getDb.run(NodeAgreementsTQ.rows ++= testNodeAgreements), AWAITDURATION)
      testCode(testNodeAgreements)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeAgreementsTQ.rows.filter(_.agId inSet testNodeAgreements.map(_.agId)).delete), AWAITDURATION)
  }
  
  def fixtureNodes(testCode: Seq[NodeRow] ⇒ Any, testData: Seq[NodeRow]): Any = {
    val testNodes: Seq[NodeRow] = testData
    
    try {
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows ++= testNodes), AWAITDURATION)
      testCode(testNodes)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodesTQ.rows.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  def fixturePolicies(testCode: Seq[BusinessPolicyRow] ⇒ Any, testData: Seq[BusinessPolicyRow]): Any = {
    val testPolicies: Seq[BusinessPolicyRow] = testData
    
    try {
      Await.result(DBCONNECTION.getDb.run(BusinessPoliciesTQ.rows ++= testPolicies), AWAITDURATION)
      testCode(testPolicies)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(BusinessPoliciesTQ.rows.filter(_.businessPolicy inSet testPolicies.map(_.businessPolicy)).delete), AWAITDURATION)
  }
  
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - changeSince < 0L") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(-1L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 400 Bad request - numEntries < 0") {
    val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, Some(-1), 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- Initial API Call From Agbot - No Nodes") {
    fixturePolicies(_ ⇒ {
      val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.NOT_FOUND.intValue)
  
      val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
      assert(responseBody.nodes.isEmpty)
      assert(responseBody.offsetUpdated === false)
  
      val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
      assert(offset.nonEmpty)
      assert(offset.head._1.isEmpty)
      assert(offset.head._2 === 0L)
    }, TESTPOLICIES)
  }
  
  test("POST /org/" + "TestPolicySearchPost" + "/business/policy/" + "pol1" + "/search -- 409 bad session - Agbot session desynchronization") {
    fixturePolicies(_ ⇒ {
      Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 1L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
      info("code: " + response.code)
      info("body: " + response.body)
      assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  
      val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
      assert(responseBody.nodes.isEmpty)
      assert(responseBody.offsetUpdated === false)
  
      val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
      assert(offset.nonEmpty)
      assert(offset.head._1.isEmpty)
      assert(offset.head._2 === 1L)
    }, TESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "mybuspolsdr" + "/search - all nodes (no agreements yet)") {
    fixturePolicies(_ ⇒ {
      fixtureNodes(
        _ ⇒ {
          fixtureNodeAgreements(_ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "mybuspolsdr" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            val postSearchDevResp: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            val nodes: Seq[BusinessPolicyNodeResponse] = postSearchDevResp.nodes
            assert(nodes.length === 4) // we created 4 nodes in this org
            assert(nodes.count(d => d.id === "TestPolicySearchPost/n1" || d.id === "TestPolicySearchPost/n2" || d.id === "TestPolicySearchPost/n3" || d.id === "TestPolicySearchPost/n4") === 4)
            val dev: BusinessPolicyNodeResponse = nodes.find(d => d.id == "TestPolicySearchPost/n1").get
            assert(dev.publicKey === "NODEABC")
            assert(dev.nodeType === NodeType.DEVICE.toString) // this node defaulted to this value
            assert(nodes.find(_.id === "TestPolicySearchPost/n2").get.nodeType === NodeType.CLUSTER.toString)
            assert(nodes.find(_.id === "TestPolicySearchPost/n4").get.nodeType === NodeType.DEVICE.toString)
          }, OLDTESTNODEAGREEMENTS)
      
        }, OLDTESTNODES)
    }, OLDTESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "mybuspolnetspeed" + "/search - as agbot") {
    fixturePolicies(_ ⇒ {
      fixtureNodes(
        _ ⇒ {
          fixtureNodeAgreements(_ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "mybuspolnetspeed" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.POST_OK.intValue)
            val postSearchDevResp: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            val nodes: Seq[BusinessPolicyNodeResponse] = postSearchDevResp.nodes
            assert(nodes.length === 3) // we created 4 nodes in this org, but nodeId4 is arm
          }, OLDTESTNODEAGREEMENTS)
        }, OLDTESTNODES
      )
    }, OLDTESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "mybuspolsdr" + "/search - 1 node in sdr agreement") {
    fixturePolicies(_ ⇒ {
      fixtureNodes(
        _ ⇒ {
          fixtureNodeAgreements(_ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "mybuspolsdr" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: "+response.code)
            assert(response.code === HttpCode.POST_OK.intValue)
            val postSearchDevResp: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            val nodes: Seq[BusinessPolicyNodeResponse] = postSearchDevResp.nodes
            assert(nodes.length === 3)
            assert(nodes.count(d => d.id === "TestPolicySearchPost/n2" || d.id === "TestPolicySearchPost/n3" || d.id === "TestPolicySearchPost/n4") === 3)
          },
            OLDTESTNODEAGREEMENTS2)}, OLDTESTNODES
      )
    }, OLDTESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "mybuspolnetspeed" + "/search - 1 node in sdr agreement, but that shouldn't affect this") {
    fixturePolicies(_ ⇒ {
      fixtureNodes(
        _ ⇒ {
          fixtureNodeAgreements(_ ⇒ {
            val response: HttpResponse[String] = Http(URL + "/business/policies/" + "mybuspolnetspeed" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
            info("code: " + response.code)
            assert(response.code === HttpCode.POST_OK.intValue)
            val postSearchDevResp: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
            val nodes: Seq[BusinessPolicyNodeResponse] = postSearchDevResp.nodes
            assert(nodes.length === 3)
            assert(nodes.count(d => d.id === "TestPolicySearchPost/n1" || d.id === "TestPolicySearchPost/n2" || d.id === "TestPolicySearchPost/n3") === 3)
          },
            OLDTESTNODEAGREEMENTS2)}, OLDTESTNODES
      )
    }, OLDTESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "mybuspolsdr" + "/search - the pws agreement shouldn't affect this") {
    fixturePolicies(_ ⇒ {
      fixtureNodes(_ ⇒ {
        fixtureNodeAgreements(_ ⇒ {
          val response: HttpResponse[String] = Http(URL + "/business/policies/" + "mybuspolsdr" + "/search").postData(write(PostBusinessPolicySearchRequest(0L, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
          info("code: " + response.code)
          assert(response.code === HttpCode.POST_OK.intValue)
          val postSearchDevResp: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
          val nodes: Seq[BusinessPolicyNodeResponse] = postSearchDevResp.nodes
          assert(nodes.length === 3)
          assert(nodes.count(d => d.id === "TestPolicySearchPost/n2" || d.id === "TestPolicySearchPost/n3" || d.id === "TestPolicySearchPost/n4") === 3)
        },
          OLDTESTNODEAGREEMENTS3)
      },
        OLDTESTNODES)
    }, OLDTESTPOLICIES)
  }
  
  test("POST /orgs/" + "TestPolicySearchPost" + "/business/policies/" + "pol1" + "/search - Stale node") {
    val staleNode: Seq[NodeRow] =
      Seq(NodeRow(id = "TestPolicySearchPost/n1",
                  orgid = "TestPolicySearchPost",
                  token = "",
                  name = "",
                  owner = "TestPolicySearchPost/u1",
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
                  lastUpdated = ApiTime.nowUTC)) //ApiTime.beginningUTC
  
    fixturePolicies(_ ⇒ {
      fixtureNodes(_ ⇒ {
        val response: HttpResponse[String] = Http(URL + "/business/policies/" + "pol1" + "/search").postData(write(PostBusinessPolicySearchRequest(ApiTime.nowSeconds, None, None, 0L))).headers(CONTENT).headers(ACCEPT).headers(AGBOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        assert(response.code === HttpCode.NOT_FOUND.intValue)
  
        val responseBody: PostBusinessPolicySearchResponse = parse(response.body).extract[PostBusinessPolicySearchResponse]
        assert(responseBody.nodes.isEmpty)
        assert(responseBody.offsetUpdated === false)
  
        val offset: Seq[(Option[String], Long)] = Await.result(DBCONNECTION.getDb.run(SearchOffsetPolicyTQ.getOffsetSession("TestPolicySearchPost/a1", "TestPolicySearchPost/pol1").result), AWAITDURATION)
        assert(offset.nonEmpty)
        assert(offset.head._1.isEmpty)
        assert(offset.head._2 === 0L)
      }, staleNode)
    }, TESTPOLICIES)
  }
}
