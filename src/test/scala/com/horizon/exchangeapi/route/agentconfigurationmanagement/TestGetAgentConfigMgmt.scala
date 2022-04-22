package com.horizon.exchangeapi.route.agentconfigurationmanagement

import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsResponse, NodeRow, NodesTQ, OrgRow, OrgsTQ, Prop, RegService, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetAgbotMsgsResponse, HttpCode, PostPutUsersRequest, PutAgbotsRequest, PutNodesRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover, Suite}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
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
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestGetAgentConfigMgmt"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestGetAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestGetAgentConfigMgmt",
             owner         = "TestGetAgentConfigMgmt/u1",
             publicKey     = "",
             token         = "$2a$10$RdMlsjB6jwIaoqJNIoCUieM710YLHDYGuRD.y8q0IpqFufkor1by6")  // TestGetAgentConfigMgmt/a1:a1tok
  private val TESTNODE: Seq[NodeRow] =
    Seq(NodeRow(id = "TestGetAgentConfigMgmt/n1",
                orgid = "TestGetAgentConfigMgmt",
                token = "$2a$04$qMlSbnMbLt6PyYZY3PbwEOQdlN0/Kginx9oiD1Jx9woGK7CiPUe1e",  // TestGetAgentConfigMgmt/n1:n1tok
                name = "",
                owner = "TestGetAgentConfigMgmt/u1",
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
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = true,
                email       = "",
                hashedPw    = "$2a$10$CpZ3bjz0LxkxioZUO4bM4euITQQ6C0523SYLp2ziamYd/G84YtEJy",  // TestGetAgentConfigMgmt/admin1:admin1pw
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestGetAgentConfigMgmt",
                updatedBy   = "",
                username    = "TestGetAgentConfigMgmt/admin1"),
        UserRow(admin       = false,
                email       = "",
                hashedPw    = "$2a$10$277Ds6AvpLchRM7UBslfpuoS1cU8rFvdv9vG3lXnmVCigws90WBl.",  // TestGetAgentConfigMgmt/u1:a1pw
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "TestGetAgentConfigMgmt",
                updatedBy   = "",
                username    = "TestGetAgentConfigMgmt/u1"))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT) andThen
                                       (NodesTQ ++= TESTNODE)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
   Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(change => {(change.orgId startsWith "TestPutAgentConfigMgmt") ||
                                                                          (change.orgId === "IBM" &&
                                                                           change.resource === "agentfileversion")}).delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestGetAgentConfigMgmt").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  
  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }
  
  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(java.sql.Timestamp, String)] => Any, testData: Seq[(java.sql.Timestamp, String)]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
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
                   owner         = "TestGetAgentConfigMgmt/u1",
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
                  owner = "TestGetAgentConfigMgmt/u1",
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
    val TESTUSERS: Seq[UserRow] =
      Seq(UserRow(admin       = true,
                  email       = "",
                  hashedPw    = "$2a$10$jzpB/Kxf6l4mnBniTKNS6uo0rTRtywIAh4l6fj8WOAPIVHq03X0AG",  // IBM/TestGetAgentConfigMgmt-admin1:TestGetAgentConfigMgmt-admin1pw
                  hubAdmin    = false,
                  lastUpdated = ApiTime.nowUTC,
                  orgid       = "IBM",
                  updatedBy   = "",
                  username    = "IBM/TestGetAgentConfigMgmt-admin1"))
    
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
    val TESTUSERS: Seq[UserRow] =
      Seq(UserRow(admin       = false,
                  email       = "",
                  hashedPw    = "$2a$10$qCFvL9XKLJPH0rKCn2qNWuAXEmjppuIkyl2bQZOPi/XgtyUzxYEQm",  // IBM/TestGetAgentConfigMgmt-u1:TestGetAgentConfigMgmt-u1pw
                  hubAdmin    = false,
                  lastUpdated = ApiTime.nowUTC,
                  orgid       = "IBM",
                  updatedBy   = "",
                  username    = "IBM/TestGetAgentConfigMgmt-u1"))
    
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
