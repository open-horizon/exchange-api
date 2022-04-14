package com.horizon.exchangeapi.route.agentconfigurationmanagement

import com.horizon.exchangeapi.tables.{AgbotMsgRow, AgbotMsgsTQ, AgbotRow, AgbotsTQ, AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsRequest, AgentVersionsResponse, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResourceChangeRow, ResourceChangesTQ, SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetAgbotMsgsResponse, HttpCode, PostPutUsersRequest, PutAgbotsRequest, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, DoNotDiscover, Suite}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

@DoNotDiscover
class TestPutAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with Suite {
  private val ACCEPT = ("Accept", "application/json")
  private val CONTENT = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  // private val ORGID = "TestPutAgentConfigMgmt"
  private val ROOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats = DefaultFormats
  
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id = "TestPutAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint = "",
             name = "",
             orgid = "TestPutAgentConfigMgmt",
             owner = "TestPutAgentConfigMgmt/u1",
             publicKey = "",
             token = "$2a$10$mVoFqGenNc9Z7/HpNwnaiuKuNmdXxy6VHlliUZMDR280Ec5vNXXka") // TestPutAgentConfigMgmt/a1:a1tok
  private val TESTORGANIZATIONS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description = "",
               label = "",
               lastUpdated = ApiTime.nowUTC,
               limits = "",
               orgId = "TestPutAgentConfigMgmt",
               orgType = "",
               tags = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin = true,
                email = "",
                hashedPw = "$2a$10$LNH5rZACF8YnbHWtUFnULOxNecpZoq6qXG0iI47OBCdNtugUehRLG", // TestPutAgentConfigMgmt/admin1:admin1pw
                hubAdmin = false,
                lastUpdated = ApiTime.nowUTC,
                orgid = "TestPutAgentConfigMgmt",
                updatedBy = "",
                username = "TestPutAgentConfigMgmt/admin1"),
        UserRow(admin = false,
                email = "",
                hashedPw = "$2a$10$DGVQ73YXt2IXtxA3bMmxSu0q5wEj26UgE.6hGryB5BedV1E945yki", // TestPutAgentConfigMgmt/u1:a1pw
                hubAdmin = false,
                lastUpdated = ApiTime.nowUTC,
                orgid = "TestPutAgentConfigMgmt",
                updatedBy = "",
                username = "TestPutAgentConfigMgmt/u1"))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT)
    ), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPutAgentConfigMgmt").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPutAgentConfigMgmt").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete andThen
                                       AgentConfigurationVersionsTQ.delete andThen
                                       AgentSoftwareVersionsTQ.delete andThen
                                       AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }
  
  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(java.sql.Timestamp, String)] => Any, testData: Seq[(java.sql.Timestamp, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  test("PUT /v1/orgs/randomOrg/AgentFileVersion -- 400 Bad Input - Bad Org ") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    val TESTCHG: Seq[(java.sql.Timestamp, String)] = Seq((ApiTime.nowTimestamp, "IBM"))
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
        agentConfigVersions = TESTCONFIG,
        agentSoftwareVersions = TESTSOFT)
    
    val request: HttpResponse[String] = Http(URL + "randomOrg/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - IBM User") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
        agentConfigVersions = TESTCONFIG,
        agentSoftwareVersions = TESTSOFT)
    
    val TESTCHG: Seq[(java.sql.Timestamp, String)] = Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] =
      Seq(UserRow(admin       = false,
        email       = "",
        hashedPw    = "$2a$10$LZrdek.gT5GPf7aJCqKXhutbYbhMm60BdI3Ylh2dPD3aQnERE/grC",  // IBM/TestPutAgentConfigMgmt-u1:TestPutAgentConfigMgmt-u1pw
        hubAdmin    = false,
        lastUpdated = ApiTime.nowUTC,
        orgid       = "IBM",
        updatedBy   = "",
        username    = "IBM/TestPutAgentConfigMgmt-u1"))
    
    fixtureUsers(
      _ => {
        val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestPutAgentConfigMgmt-u1:TestPutAgentConfigMgmt-u1pw"))).asString
        info("code: " + request.code)
        info("body: " + request.body)
        
        assert(request.code === HttpCode.ACCESS_DENIED.intValue)
      }, TESTUSERS)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 403 Unauthorized Access - TestPutAgentConfigMgmt Organization Admin") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)
    
    val TESTCHG: Seq[(java.sql.Timestamp, String)] = Seq((ApiTime.nowTimestamp, "IBM"))
    
    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPutAgentConfigMgmt/admin1:admin1pw"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Created - Root") {
    val TESTCERT: Seq[String] = Seq("1.1.1")
    val TESTCONFIG: Seq[String] = Seq("2.2.2")
    val TESTSOFT: Seq[String] = Seq("3.4.3")

    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)

    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.PUT_OK.intValue)
    
    val versions: AgentVersionsRequest = parse(request.body).extract[AgentVersionsRequest]
    
    val certificates: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
    val changed: Seq[(java.sql.Timestamp, String)] = Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.result), AWAITDURATION)
    val configurations: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
    val resource: Seq[ResourceChangeRow] =
      Await.result(
        DBCONNECTION.getDb.run(
          ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                           .filter(_.id === "IBM")
                           .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                           .filter(_.orgId === "IBM")
                           .filter(_.resource === ResChangeCategory.ORG.toString)
                           .sortBy(_.changeId.desc)
                           .result
        ), AWAITDURATION)
    val software: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
    
    assert(certificates.length === 1)
    assert(changed.length === 1)
    assert(configurations.length === 1)
    assert(resource.nonEmpty)
    assert(software.length === 1)
    
    assert(certificates.head._1  === TESTCERT(0))
    assert(certificates.head._2  === "IBM")
    
    assert(configurations.head._1  === TESTCONFIG(0))
    assert(configurations.head._2  === "IBM")
    
    assert(software.head._1  === "IBM")
    assert(software.head._2  === TESTSOFT(0))
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Updated - Root") {
    Await.ready(DBCONNECTION.getDb.run((AgentCertificateVersionsTQ += ("1.0.0", "IBM")) andThen
                                       (AgentConfigurationVersionsTQ += ("1.0.1", "IBM")) andThen
                                       (AgentSoftwareVersionsTQ += ("IBM", "1.1.0")) andThen
                                       (AgentVersionsChangedTQ += (ApiTime.nowUTCTimestamp, "IBM"))), AWAITDURATION)
    
    val TESTCERT: Seq[String] = Seq("3.1.1")
    val TESTCONFIG: Seq[String] = Seq("6.2.2")
    val TESTSOFT: Seq[String] = Seq("9.3.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)
    
    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.PUT_OK.intValue)
    
    val certificates: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
    val changed: Seq[(java.sql.Timestamp, String)] = Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.result), AWAITDURATION)
    val configurations: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
    val resource: Seq[ResourceChangeRow] =
      Await.result(
        DBCONNECTION.getDb.run(
          ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                           .filter(_.id === "IBM")
                           .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                           .filter(_.orgId === "IBM")
                           .filter(_.resource === ResChangeCategory.ORG.toString)
                           .sortBy(_.changeId.desc)
                           .result
        ), AWAITDURATION)
    val software: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
    
    assert(certificates.length === 1)
    assert(changed.length === 1)
    assert(configurations.length === 1)
    assert(resource.nonEmpty)
    assert(software.length === 1)
    
    assert(certificates.head._1 === TESTCERT(0))
    assert(certificates.head._2 === "IBM")
    
    assert(configurations.head._1 === TESTCONFIG(0))
    assert(configurations.head._2 === "IBM")
    
    assert(software.head._1 === "IBM")
    assert(software.head._2 === TESTSOFT(0))
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Created - IBM Agbot") {
    val TESTAGBOT: Seq[AgbotRow] =
      Seq(AgbotRow(id = "IBM/TestPutAgentConfigMgmt-a1",
                   lastHeartbeat = ApiTime.nowUTC,
                   msgEndPoint = "",
                   name = "",
                   orgid = "IBM",
                   owner = "TestPutAgentConfigMgmt/u1",
                   publicKey = "",
                   token = "$2a$10$gNEK9DtpAIvPERHItqvr5uOGyQLJiuZWaEjC0QMGT5Cf3BkNQ5v.y")) // IBM/TestPutAgentConfigMgmt-a1:a1tok
    val TESTCERT: Seq[String] = Seq("1.1.1")
    val TESTCONFIG: Seq[String] = Seq("2.2.2")
    val TESTSOFT: Seq[String] = Seq("3.4.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)
        
    fixtureAgbots(
      _ => {
        val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestPutAgentConfigMgmt-a1:TestPutAgentConfigMgmt-a1tok"))).asString
        info("code: " + request.code)
        info("body: " + request.body)
  
        assert(request.code === HttpCode.PUT_OK.intValue)
  
        val versions: AgentVersionsRequest = parse(request.body).extract[AgentVersionsRequest]
  
        val certificates: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
        val changed: Seq[(java.sql.Timestamp, String)] = Await.result(DBCONNECTION.getDb.run(AgentVersionsChangedTQ.result), AWAITDURATION)
        val configurations: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
        val software: Seq[(String, String)] = Await.result(DBCONNECTION.getDb.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
  
        assert(certificates.length === 1)
        assert(changed.length === 1)
        assert(configurations.length === 1)
        assert(software.length === 1)
  
        assert(certificates.head._1  === TESTCERT(0))
        assert(certificates.head._2  === "IBM")
  
        assert(configurations.head._1  === TESTCONFIG(0))
        assert(configurations.head._2  === "IBM")
  
        assert(software.head._1  === "IBM")
        assert(software.head._2  === TESTSOFT(0))
      }, TESTAGBOT)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Created - IBM Organization Admin") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)
    
    val TESTCHG: Seq[(java.sql.Timestamp, String)] = Seq((ApiTime.nowTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] =
      Seq(UserRow(admin       = true,
                  email       = "",
                  hashedPw    = "$2a$10$XWF8g7Y0bSt7GkmzH0hKQu.J2NmA8snpGFcSMOyf2wDQpM4t3hB92",  // IBM/TestPutAgentConfigMgmt-admin1:TestPutAgentConfigMgmt-admin1pw
                  hubAdmin    = false,
                  lastUpdated = ApiTime.nowUTC,
                  orgid       = "IBM",
                  updatedBy   = "",
                  username    = "IBM/TestPutAgentConfigMgmt-admin1"))
    
    fixtureUsers(
      _ => {
        val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("IBM/TestPutAgentConfigMgmt-admin1:TestPutAgentConfigMgmt-admin1pw"))).asString
        info("code: " + request.code)
        info("body: " + request.body)
        
        assert(request.code === HttpCode.PUT_OK.intValue)
      }, TESTUSERS)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Created - TestPutAgentConfigMgmt Agbot") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    
    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)
    
    val TESTCHG: Seq[(java.sql.Timestamp, String)] = Seq((ApiTime.nowTimestamp, "IBM"))
    
    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPutAgentConfigMgmt/a1:a1tok"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.PUT_OK.intValue)
  }
}
