package org.openhorizon.exchangeapi.route.agent

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotMsgsResponse, PutAgbotsRequest}
import org.openhorizon.exchangeapi.route.organization.{ChangeEntry, ResourceChangesRequest, ResourceChangesRespObject}
import org.openhorizon.exchangeapi.route.user.PostPutUsersRequest
import org.openhorizon.exchangeapi.table.agent.{AgentVersionsChangedTQ, AgentVersionsRequest, AgentVersionsResponse}
import org.openhorizon.exchangeapi.table.agent.certificate.AgentCertificateVersionsTQ
import org.openhorizon.exchangeapi.table.agent.configuration.AgentConfigurationVersionsTQ
import org.openhorizon.exchangeapi.table.agent.software.AgentSoftwareVersionsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.{SearchOffsetPolicyAttributes, SearchOffsetPolicyTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, DoNotDiscover, Suite}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

@DoNotDiscover
class TestPutAgentConfigMgmt extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach with Suite {
  private val ACCEPT: (String, String) = ("Accept", "application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  // private val ORGID = "TestPutAgentConfigMgmt"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  
  private implicit val formats: Formats = DefaultFormats.withLong
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
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
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = "TestPutAgentConfigMgmt",
                password     = Option(Password.hash("admin1pw")),
                username     = "admin1"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "TestPutAgentConfigMgmt",
                password     = Option(Password.hash("u1pw")),
                username     = "u1"))
  private val TESTAGBOT: AgbotRow =
    AgbotRow(id = "TestPutAgentConfigMgmt/a1",
             lastHeartbeat = ApiTime.nowUTC,
             msgEndPoint = "",
             name = "",
             orgid = "TestPutAgentConfigMgmt",
             owner = TESTUSERS(1).user,
             publicKey = "",
             token = Password.hash("a1tok")) // TestPutAgentConfigMgmt/a1:a1tok
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGANIZATIONS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ += TESTAGBOT)
    ), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(change => {(change.orgId startsWith "TestPutAgentConfigMgmt") ||
                                                                           (change.orgId === "IBM" &&
                                                                            change.resource === "agentfileversion")}).delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPutAgentConfigMgmt").delete), AWAITDURATION)
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(AgentCertificateVersionsTQ.delete andThen
                                       AgentConfigurationVersionsTQ.delete andThen
                                       AgentSoftwareVersionsTQ.delete andThen
                                       AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  // Agreement Bots that are dynamically needed, specific to the test case.
  def fixtureAgbots(testCode: Seq[AgbotRow] => Any, testData: Seq[AgbotRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(AgbotsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgbotsTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureAgentCertVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try {
      Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Configuration Versions that are dynamically needed, specific to the test case.
  def fixtureAgentConfigVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try {
      Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Software Versions that are dynamically needed, specific to the test case.
  def fixtureAgentSoftVersions(testCode: Seq[(String, String, Option[Long])] => Any, testData: Seq[(String, String, Option[Long])]): Any = {
    try {
      Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ.delete), AWAITDURATION)
  }
  
  // Agent Certificate Versions that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }
  
  // Users that are dynamically needed, specific to the test case.
  def fixtureVersionsChanged(testCode: Seq[(Instant, String)] => Any, testData: Seq[(Instant, String)]): Any = {
    try {
      Await.result(DBCONNECTION.run(AgentVersionsChangedTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(AgentVersionsChangedTQ.delete), AWAITDURATION)
  }
  
  
  test("PUT /v1/orgs/randomOrg/AgentFileVersion -- 400 Bad Input - Bad Org ") {
    val TESTCERT: Seq[String] = Seq("1.1.1", "IBM")
    val TESTCONFIG: Seq[String] = Seq("2.2.2", "IBM")
    val TESTSOFT: Seq[String] = Seq("IBM", "3.3.3")
    val TESTCHG: Seq[(Instant, String)] = Seq((ApiTime.nowUTCTimestamp, "IBM"))
    
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
    
    val TESTCHG: Seq[(Instant, String)] = Seq((ApiTime.nowUTCTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] = {
      Seq(UserRow(createdAt    = TIMESTAMP,
                  isHubAdmin   = false,
                  isOrgAdmin   = false,
                  modifiedAt   = TIMESTAMP,
                  organization = "IBM",
                  password     = Option(Password.hash("TestPutAgentConfigMgmt-u1pw")),
                  username     = "TestPutAgentConfigMgmt-u1"))
    }
    
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
    
    val TESTCHG: Seq[(Instant, String)] = Seq((ApiTime.nowUTCTimestamp, "IBM"))
    
    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPutAgentConfigMgmt/admin1:admin1pw"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Created - Root") {
    val TESTCERT: Seq[String] = Seq("1.1.1.1", "1.1.1")
    val TESTCONFIG: Seq[String] = Seq("2.2.2.2", "2.2.2")
    val TESTSOFT: Seq[String] = Seq("3.3.3.3", "3.3.3")

    val requestBody: AgentVersionsRequest =
      AgentVersionsRequest(agentCertVersions = TESTCERT,
                           agentConfigVersions = TESTCONFIG,
                           agentSoftwareVersions = TESTSOFT)

    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.PUT_OK.intValue)
    
    val versions: AgentVersionsRequest = parse(request.body).extract[AgentVersionsRequest]
    
    val certificates: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ.sortBy(_.priority.asc.nullsLast).result), AWAITDURATION)
    val changed: Seq[(Instant, String)] = Await.result(DBCONNECTION.run(AgentVersionsChangedTQ.result), AWAITDURATION)
    val configurations: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ.sortBy(_.priority.asc.nullsLast).result), AWAITDURATION)
    val resource: Seq[ResourceChangeRow] =
      Await.result(
        DBCONNECTION.run(
          ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                           .filter(_.id === "IBM")
                           .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                           .filter(_.orgId === "IBM")
                           .filter(_.resource === ResChangeResource.AGENTFILEVERSION.toString)
                           .sortBy(_.changeId.desc)
                           .result
        ), AWAITDURATION)
    val software: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ.sortBy(_.priority.asc.nullsLast).result), AWAITDURATION)
    
    assert(certificates.length === 2)
    assert(changed.length === 1)
    assert(configurations.length === 2)
    assert(resource.nonEmpty)
    assert(software.length === 2)
    
    assert(certificates.head._1  === TESTCERT(0))
    assert(certificates.head._2  === "IBM")
    assert(certificates.head._3.getOrElse(-1)  === 0)
    assert(certificates(1)._1  === TESTCERT(1))
    assert(certificates(1)._2  === "IBM")
    assert(certificates(1)._3.getOrElse(-1)  === 1)
    
    assert(configurations.head._1  === TESTCONFIG(0))
    assert(configurations.head._2  === "IBM")
    assert(configurations.head._3.getOrElse(-1)  === 0)
    assert(configurations(1)._1  === TESTCONFIG(1))
    assert(configurations(1)._2  === "IBM")
    assert(configurations(1)._3.getOrElse(-1)  === 1)
    
    assert(software.head._1  === "IBM")
    assert(software.head._2  === TESTSOFT(0))
    assert(certificates.head._3.getOrElse(-1)  === 0)
    assert(software(1)._1  === "IBM")
    assert(software(1)._2  === TESTSOFT(1))
    assert(software(1)._3.getOrElse(-1)  === 1)
    
    val request2: HttpResponse[String] = Http(URL + "TestPutAgentConfigMgmt/changes").postData(write(ResourceChangesRequest(changeId = 0, lastUpdated = None, maxRecords = 1000, orgList = None))).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPutAgentConfigMgmt/u1:u1pw"))).asString
    info("code: " + request2.code)
    info("body: " + request2.body)
    
    assert(request2.code === HttpCode.POST_OK.intValue)
    
    val versionChanges: List[ChangeEntry] = parse(request2.body).extract[ResourceChangesRespObject].changes.filter(change => {change.orgId === "IBM" && change.resource === "agentfileversion"})
    
    assert(versionChanges.head.id === "IBM")
    assert(versionChanges.head.operation === "modified")
    assert(versionChanges.head.orgId === "IBM")
    assert(versionChanges.head.resource === "agentfileversion")
  }
  
  test("PUT /v1/orgs/IBM/AgentFileVersion -- 201 Updated - Root") {
    Await.ready(DBCONNECTION.run((AgentCertificateVersionsTQ += ("1.0.0", "IBM", None)) andThen
                                       (AgentConfigurationVersionsTQ += ("1.0.1", "IBM", None)) andThen
                                       (AgentSoftwareVersionsTQ += ("IBM", "1.1.0", None)) andThen
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
    
    val certificates: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
    val changed: Seq[(Instant, String)] = Await.result(DBCONNECTION.run(AgentVersionsChangedTQ.result), AWAITDURATION)
    val configurations: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
    val resource: Seq[ResourceChangeRow] =
      Await.result(
        DBCONNECTION.run(
          ResourceChangesTQ.filter(_.category === ResChangeCategory.ORG.toString)
                           .filter(_.id === "IBM")
                           .filter(_.operation === ResChangeOperation.MODIFIED.toString)
                           .filter(_.orgId === "IBM")
                           .filter(_.resource === ResChangeResource.AGENTFILEVERSION.toString)
                           .sortBy(_.changeId.desc)
                           .result
        ), AWAITDURATION)
    val software: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
    
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
                   owner = TESTUSERS(1).user,
                   publicKey = "",
                   token = Password.hash("TestPutAgentConfigMgmt-a1tok"))) // IBM/TestPutAgentConfigMgmt-a1:a1tok
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
  
        val certificates: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentCertificateVersionsTQ.result), AWAITDURATION)
        val changed: Seq[(Instant, String)] = Await.result(DBCONNECTION.run(AgentVersionsChangedTQ.result), AWAITDURATION)
        val configurations: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentConfigurationVersionsTQ.result), AWAITDURATION)
        val software: Seq[(String, String, Option[Long])] = Await.result(DBCONNECTION.run(AgentSoftwareVersionsTQ.result), AWAITDURATION)
  
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
    
    val TESTCHG: Seq[(Instant, String)] = Seq((ApiTime.nowUTCTimestamp, "IBM"))
    val TESTUSERS: Seq[UserRow] = {
      Seq(UserRow(createdAt    = TIMESTAMP,
                  isHubAdmin   = false,
                  isOrgAdmin   = true,
                  modifiedAt   = TIMESTAMP,
                  organization = "IBM",
                  password     = Option(Password.hash("TestPutAgentConfigMgmt-admin1pw")),
                  username     = "TestPutAgentConfigMgmt-admin1"))
    }
    
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
    
    val TESTCHG: Seq[(Instant, String)] = Seq((ApiTime.nowUTCTimestamp, "IBM"))
    
    val request: HttpResponse[String] = Http(URL + "IBM/AgentFileVersion").put(write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPutAgentConfigMgmt/a1:a1tok"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === HttpCode.PUT_OK.intValue)
  }
}
