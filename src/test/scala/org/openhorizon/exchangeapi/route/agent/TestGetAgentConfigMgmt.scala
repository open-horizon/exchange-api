package org.openhorizon.exchangeapi.route.agent

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotMsgsResponse, PutAgbotsRequest}
import org.openhorizon.exchangeapi.route.node.PutNodesRequest
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.agent.{AgentVersionsChangedTQ, AgentVersionsResponse}
import org.openhorizon.exchangeapi.table.agent.certificate.AgentCertificateVersionsTQ
import org.openhorizon.exchangeapi.table.agent.configuration.AgentConfigurationVersionsTQ
import org.openhorizon.exchangeapi.table.agent.software.AgentSoftwareVersionsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.{SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ, Prop, RegService}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover, Suite}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.ZoneId
import scala.collection.immutable.List
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

@DoNotDiscover
class TestGetAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll with Suite {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  // private val ORGID = "TestGetAgentConfigMgmt"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: java.sql.Timestamp = ApiTime.nowUTCTimestamp
  
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = "TestGetAgentConfigMgmt",
                password     = Option(Password.fastHash("admin1pw")),
                username     = "admin1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestGetAgentConfigMgmt",
                password     = Option(Password.fastHash("u1pw")),
                username     = "u1"))
  private val TESTNODE: Seq[NodeRow] =
    Seq(NodeRow(id = "TestGetAgentConfigMgmt/n1",
                orgid = "TestGetAgentConfigMgmt",
                token = "$2a$04$qMlSbnMbLt6PyYZY3PbwEOQdlN0/Kginx9oiD1Jx9woGK7CiPUe1e",  // TestGetAgentConfigMgmt/n1:n1tok
                name = "",
                owner = TESTUSERS(1).user,
                nodeType = "device",
                pattern = "",
                regServices = "[]",
                userInput = "",
                msgEndPoint = "",
                softwareVersions = "",
                lastHeartbeat = Option(ApiTime.nowUTC),
                publicKey = "key",
                arch = "arm",
                heartbeatIntervals = "",
                lastUpdated = ApiTime.nowUTC))
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "",
               label              = "",
               lastUpdated        = ApiTime.nowUTC,
               limits             = "",
               orgId              = "TestGetAgentConfigMgmt",
               orgType            = "",
               tags               = None))
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestGetAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestGetAgentConfigMgmt",
             owner         = TESTUSERS(1).user,
             publicKey     = "",
             token         = "$2a$10$RdMlsjB6jwIaoqJNIoCUieM710YLHDYGuRD.y8q0IpqFufkor1by6")  // TestGetAgentConfigMgmt/a1:a1tok
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (NodesTQ ++= TESTNODE)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
   Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(change => {(change.orgId startsWith "TestPutAgentConfigMgmt") ||
                                                                          (change.orgId === "IBM" &&
                                                                           change.resource === "agentfileversion")}).delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestGetAgentConfigMgmt").delete), AWAITDURATION)
  }
  
  
  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try{
      Await.result(DBCONNECTION.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
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
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try{
      Await.result(DBCONNECTION.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }
  
  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(java.sql.Timestamp, String)] => Any, testData: Seq[(java.sql.Timestamp, String)]): Any = {
    try{
      Await.result(DBCONNECTION.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  test("GET /v1/orgs/somerandomorg/AgentFileVersion -- 400 Bad Input - Bad Org") {
    val response: HttpResponse[String] = Http(URL + "somerandomorg/AgentFileVersion").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 404 Not Found") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - Root") {
    val TESTCERT: Seq[(String, String, Option[Long])] =
     Seq(("1.1.1", "IBM", Option(1L)),
         ("some other version", "IBM", Option(0L)))
    val TESTCONFIG: Seq[(String, String, Option[Long])] =
      Seq(("second", "IBM", None),
          ("some other version", "IBM", Option(1L)))
    val TESTSOFT: Seq[(String, String, Option[Long])] =
      Seq(("IBM", "3.3.3-a", Option(10L)),
          ("IBM", "some other version", Option(4L)))
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
  
    fixtureAgentCertVersions(
      _ => {
        fixtureAgentConfigVersions(
          _ => {
            fixtureAgentSoftVersions(
              _ => {
                fixtureVersionsChanged(
                  _ => {
                    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(ROOTAUTH).asString
                    info("code: " + response.code)
                    info("body: " + response.body)
                    
                    assert(response.code === HttpCode.OK.intValue)
                    
                    val versions: AgentVersionsResponse = parse(response.body).extract[AgentVersionsResponse]
                    
                    assert(versions.agentCertVersions.length     === 2)
                    assert(versions.agentConfigVersions.length   === 2)
                    assert(versions.agentSoftwareVersions.length === 2)
                    
                    assert(versions.agentCertVersions(0)     === TESTCERT(1)._1)
                    assert(versions.agentCertVersions(1)     === TESTCERT(0)._1)
                    assert(versions.agentConfigVersions(0)   === TESTCONFIG(1)._1)
                    assert(versions.agentConfigVersions(1)   === TESTCONFIG(0)._1)
                    assert(versions.agentSoftwareVersions(0) === TESTSOFT(1)._2)
                    assert(versions.agentSoftwareVersions(1) === TESTSOFT(0)._2)
                    assert(versions.lastUpdated              === TESTCHG(0)._1.toLocalDateTime.atZone(ZoneId.of("UTC")).toString)
                  }, TESTCHG)
              }, TESTSOFT)
          }, TESTCONFIG)
      }, TESTCERT)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - IBM Agbot") {
    val TESTAGBOTS: Seq[AgbotRow] =
      Seq(AgbotRow(id            = "IBM/TestGetAgentConfigMgmt-a1",
                   lastHeartbeat = ApiTime.nowUTC,
                   msgEndPoint   = "",
                   name          = "",
                   orgid         = "IBM",
                   owner         = TESTUSERS(1).user,
                   publicKey     = "",
                   token         = "$2a$10$IxvKVE5o2tzqFh/aSygDE.cqBQFGjMuWqK24EqRyTn8RkklJAlI0a"))  // IBM/TestGetAgentConfigMgmt-a1:TestGetAgentConfigMgmt-a1tok
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureAgbots(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestGetAgentConfigMgmt-a1:TestGetAgentConfigMgmt-a1tok"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
  
            assert(response.code === HttpCode.OK.intValue)
          }, TESTCHG)
      }, TESTAGBOTS)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - IBM Node") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTNODE: Seq[NodeRow] =
      Seq(NodeRow(id = "IBM/TestGetAgentConfigMgmt-n1",
                  orgid = "IBM",
                  token = "$2a$04$VKBje3vZ5DAGGZymgTJip.ish0LhvUTK0gqG4RscO0oogffHNFHgC",  // IBM/TestGetAgentConfigMgmt-n1:TestGetAgentConfigMgmt-n1tok
                  name = "",
                  owner = TESTUSERS(1).user,
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
    
    fixtureNodes(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestGetAgentConfigMgmt-n1:TestGetAgentConfigMgmt-n1tok"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
            
            assert(response.code === HttpCode.OK.intValue)
          }, TESTCHG)
      }, TESTNODE)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - IBM Organization Admin") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] = {
      Seq(UserRow(createdAt    = TIMESTAMP,
                  isHubAdmin   = false,
                  isOrgAdmin   = true,
                  modifiedAt   = TIMESTAMP,
                  organization = "IBM",
                  password     = Option(Password.hash("TestGetAgentConfigMgmt-admin1pw")),
                  username     = "TestGetAgentConfigMgmt-admin1"))
    }
    
    fixtureUsers(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestGetAgentConfigMgmt-admin1:TestGetAgentConfigMgmt-admin1pw"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
            
            assert(response.code === HttpCode.OK.intValue)
          }, TESTCHG)
      }, TESTUSERS)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - IBM User") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] = {
      Seq(UserRow(createdAt    = TIMESTAMP,
                  isHubAdmin   = false,
                  isOrgAdmin   = false,
                  modifiedAt   = TIMESTAMP,
                  organization = "IBM",
                  password     = Option(Password.hash("TestGetAgentConfigMgmt-u1pw")),
                  username     = "TestGetAgentConfigMgmt-u1"))
    }
    
    fixtureUsers(
      _ =>{
        fixtureVersionsChanged(
          _ => {
            val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestGetAgentConfigMgmt-u1:TestGetAgentConfigMgmt-u1pw"))).asString
            info("code: " + response.code)
            info("body: " + response.body)
            
            assert(response.code === HttpCode.OK.intValue)
          }, TESTCHG)
      }, TESTUSERS)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - TestGetAgentConfigMgmt Agbot") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureVersionsChanged(
      _ => {
        val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/a1:a1tok"))).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.OK.intValue)
      }, TESTCHG)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - TestGetAgentConfigMgmt Node") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))

    fixtureVersionsChanged(
      _ => {
        val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/n1:n1tok"))).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.OK.intValue)
      }, TESTCHG)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - TestGetAgentConfigMgmt Organization Admin") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureVersionsChanged(
      _ => {
        val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/admin1:admin1pw"))).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.OK.intValue)
      }, TESTCHG)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 200 Ok - TestGetAgentConfigMgmt User") {
    val TESTCHG: Seq[(java.sql.Timestamp, String)] =
      Seq((ApiTime.nowTimestamp, "IBM"))
    
    fixtureVersionsChanged(
      _ => {
        val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/u1:u1pw"))).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.OK.intValue)
      }, TESTCHG)
  }
}
