package com.horizon.exchangeapi.route.agentconfigurationmanagement

import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsResponse, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetAgbotMsgsResponse, HttpCode, PostPutUsersRequest, PutAgbotsRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import java.time.ZoneId
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT = ("Accept","application/json")
  private val CONTENT = ("Content-Type","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestGetAgentConfigMgmt"
  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats = DefaultFormats
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id            = "TestGetAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint   = "",
             name          = "",
             orgid         = "TestGetAgentConfigMgmt",
             owner         = "TestGetAgentConfigMgmt/u1",
             publicKey     = "",
             token         = "$2a$10$RdMlsjB6jwIaoqJNIoCUieM710YLHDYGuRD.y8q0IpqFufkor1by6")  // TestGetAgentConfigMgmt/a1:a1tok
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
    Seq(UserRow(admin       = false,
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
                                       (AgbotsTQ += TESTAGBOT)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
   Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestGetAgentConfigMgmt").delete andThen
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
  def fixtureAgentCertVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
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
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - TestGetAgentConfigMgmt Agbot") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/a1:a1tok"))).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - TestGetAgentConfigMgmt User") {
    val response: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestGetAgentConfigMgmt/u1:u1pw"))).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- Default") {
    val TESTCERT: Seq[(String, String)] =
     Seq(("1.1.1", "IBM"))
    val TESTCONFIG: Seq[(String, String)] =
      Seq(("2.2.2", "IBM"))
    val TESTSOFT: Seq[(String, String)] =
      Seq(("IBM", "3.3.3"))
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
                    
                    assert(versions.agentCertVersions.length     === 1)
                    assert(versions.agentConfigVersions.length   === 1)
                    assert(versions.agentSoftwareVersions.length === 1)
                    
                    assert(versions.agentCertVersions(0)     === TESTCERT(0)._1)
                    assert(versions.agentConfigVersions(0)   === TESTCONFIG(0)._1)
                    assert(versions.agentSoftwareVersions(0) === TESTSOFT(0)._2)
                    assert(versions.lastUpdated              === TESTCHG(0)._1.toLocalDateTime.atZone(ZoneId.of("UTC")).toString)
                  }, TESTCHG)
              }, TESTSOFT)
          }, TESTCONFIG)
      }, TESTCERT)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- IBM Agbot") {
    assert(true)
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
  
            val versions: AgentVersionsResponse = parse(response.body).extract[AgentVersionsResponse]
  
            assert(versions.agentCertVersions.isEmpty)
            assert(versions.agentConfigVersions.isEmpty)
            assert(versions.agentSoftwareVersions.isEmpty)
            
            assert(versions.lastUpdated === TESTCHG(0)._1.toLocalDateTime.atZone(ZoneId.of("UTC")).toString)
          }, TESTCHG)
      }, TESTAGBOTS)
  }
  
  test("GET /v1/orgs/IBM/AgentFileVersion -- IBM User") {
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
            
            val versions: AgentVersionsResponse = parse(response.body).extract[AgentVersionsResponse]
            
            assert(versions.agentCertVersions.isEmpty)
            assert(versions.agentConfigVersions.isEmpty)
            assert(versions.agentSoftwareVersions.isEmpty)
            
            assert(versions.lastUpdated === TESTCHG(0)._1.toLocalDateTime.atZone(ZoneId.of("UTC")).toString)
          }, TESTCHG)
      }, TESTUSERS)
  }
}
