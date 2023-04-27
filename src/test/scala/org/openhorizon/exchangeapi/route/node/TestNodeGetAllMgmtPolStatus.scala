package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.{GetNMPStatusResponse, ManagementPoliciesTQ, ManagementPolicyRow, NMPStatus, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, PolicyStatus, ResourceChangesTQ, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.Role
import org.openhorizon.exchangeapi.table.{GetNMPStatusResponse, ManagementPoliciesTQ, ManagementPolicyRow, NMPStatus, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, PolicyStatus}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeGetAllMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  
  private val TESTMANAGEMENTPOLICY: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "",
                            enabled          = true,
                            label            = "",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "",
                            managementPolicy = "TestNodeGetAllMgmtPolStatus/pol1",
                            orgid            = "TestNodeGetAllMgmtPolStatus",
                            owner            = "TestNodeGetAllMgmtPolStatus/u1",
                            patterns         = "",
                            properties       = "",
                            start            = ""),
        ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "",
                            enabled          = true,
                            label            = "",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "",
                            managementPolicy = "TestNodeGetAllMgmtPolStatus/pol2",
                            orgid            = "TestNodeGetAllMgmtPolStatus",
                            owner            = "TestNodeGetAllMgmtPolStatus/u1",
                            patterns         = "",
                            properties       = "",
                            start            = ""))
  private val TESTNODE: NodeRow =
    NodeRow(arch               = "",
            id                 = "TestNodeGetAllMgmtPolStatus/n1",
            heartbeatIntervals = "",
            lastHeartbeat      = Option(ApiTime.nowUTC),
            lastUpdated        = ApiTime.nowUTC,
            msgEndPoint        = "",
            name               = "",
            nodeType           = "",
            orgid              = "TestNodeGetAllMgmtPolStatus",
            owner              = "TestNodeGetAllMgmtPolStatus/u1",
            pattern            = "",
            publicKey          = "",
            regServices        = "",
            softwareVersions   = "",
            token              = "",
            userInput          = "")
  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage         = Option("pol1 description test"),
                             node                 = "TestNodeGetAllMgmtPolStatus/n1",
                             policy               = "TestNodeGetAllMgmtPolStatus/pol1",
                             status               = Option("Success"),
                             endTime              = Option(""),
                             actualStartTime      = Option(ApiTime.nowUTC),
                             scheduledStartTime   = ApiTime.nowUTC,
                             updated              = ApiTime.nowUTC,
                             certificateVersion   = Option(""),
                             configurationVersion = Option(""),
                             softwareVersion      = Option("")),
        NodeMgmtPolStatusRow(errorMessage         = None,
                             node                 = "TestNodeGetAllMgmtPolStatus/n1",
                             policy               = "TestNodeGetAllMgmtPolStatus/pol2",
                             status               = None,
                             endTime              = None,
                             actualStartTime      = None,
                             scheduledStartTime   = ApiTime.nowUTC,
                             updated              = ApiTime.nowUTC,
                             certificateVersion   = None,
                             configurationVersion = None,
                             softwareVersion      = None))
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestNodeGetAllMgmtPolStatus",
           orgType            = "",
           tags               = None,
           limits             = "")
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
            hubAdmin    = false,
            email       = "",
            hashedPw    = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeGetAllMgmtPolStatus/u1:u1pw
            lastUpdated = ApiTime.nowUTC,
            orgid       = "TestNodeGetAllMgmtPolStatus",
            updatedBy   = "",
            username    = "TestNodeGetAllMgmtPolStatus/u1")
  
  
  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ += TESTORGANIZATION) andThen
                                       (UsersTQ += TESTUSER) andThen
                                       (NodesTQ += TESTNODE) andThen
                                       (ManagementPoliciesTQ ++= TESTMANAGEMENTPOLICY) andThen
                                       (NodeMgmtPolStatuses ++= TESTNODEMGMTPOLSTATUSES)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodeGetAllMgmtPolStatus").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodeGetAllMgmtPolStatus").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }
  
  // Management Policies that are dynamically needed, specific to the test case.
  def fixtureNodeMgmtPol(testCode: Seq[ManagementPolicyRow] => Any, testData: Seq[ManagementPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(ManagementPoliciesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(ManagementPoliciesTQ.filter(_.managementPolicy inSet testData.map(_.managementPolicy)).delete), AWAITDURATION)
  }
  
  // Management Policy Statuses that are dynamically needed, specific to the test case.
  def fixtureNodeMgmtPolStatus(testCode: Seq[NodeMgmtPolStatusRow] => Any, testData: Seq[NodeMgmtPolStatusRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.filter(_.policy inSet testData.map(_.policy)).delete), AWAITDURATION)
  }
  
  test("GET /orgs/TestNodeGetAllMgmtPolStatus-someorganization/nodes/n1/managementStatus -- 404 Not Found - Organization") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetAllMgmtPolStatus-someorganization/nodes/n1/managementStatus").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/TestNodeGetAllMgmtPolStatus/nodes/somenode/managementStatus -- 404 Not Found - Node") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetAllMgmtPolStatus/nodes/somenode/managementStatus").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/TestNodeGetAllMgmtPolStatus/nodes/n1/managementStatus -- 200 Ok - root") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetAllMgmtPolStatus/nodes/n1/managementStatus").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val resp: Map[String, NMPStatus] = parse(response.body).extract[GetNMPStatusResponse].managementStatus
    assert(resp.size === 2)
    assert(resp.contains(TESTNODEMGMTPOLSTATUSES.head.policy))
    assert(resp.contains(TESTNODEMGMTPOLSTATUSES(1).policy))
    
    val status0: PolicyStatus = resp(TESTNODEMGMTPOLSTATUSES.head.policy).agentUpgradePolicyStatus
    
    assert(status0.endTime       === TESTNODEMGMTPOLSTATUSES.head.endTime.get)
    assert(status0.errorMessage  === TESTNODEMGMTPOLSTATUSES.head.errorMessage.get)
    assert(status0.lastUpdated   === TESTNODEMGMTPOLSTATUSES.head.updated)
    assert(status0.scheduledTime === TESTNODEMGMTPOLSTATUSES.head.scheduledStartTime)
    assert(status0.startTime     === TESTNODEMGMTPOLSTATUSES.head.actualStartTime.get)
    assert(status0.status        === TESTNODEMGMTPOLSTATUSES.head.status.get)
    assert(status0.upgradedVersions.certVersion     === TESTNODEMGMTPOLSTATUSES.head.certificateVersion.get)
    assert(status0.upgradedVersions.configVersion   === TESTNODEMGMTPOLSTATUSES.head.configurationVersion.get)
    assert(status0.upgradedVersions.softwareVersion === TESTNODEMGMTPOLSTATUSES.head.softwareVersion.get)
    
    val status1: PolicyStatus = resp(TESTNODEMGMTPOLSTATUSES(1).policy).agentUpgradePolicyStatus
    
    assert(status1.endTime       === TESTNODEMGMTPOLSTATUSES(1).endTime.getOrElse(""))
    assert(status1.errorMessage  === TESTNODEMGMTPOLSTATUSES(1).errorMessage.getOrElse(""))
    assert(status1.lastUpdated   === TESTNODEMGMTPOLSTATUSES(1).updated)
    assert(status1.scheduledTime === TESTNODEMGMTPOLSTATUSES(1).scheduledStartTime)
    assert(status1.startTime     === TESTNODEMGMTPOLSTATUSES(1).actualStartTime.getOrElse(""))
    assert(status1.status        === TESTNODEMGMTPOLSTATUSES(1).status.getOrElse(""))
    assert(status1.upgradedVersions.certVersion     === TESTNODEMGMTPOLSTATUSES(1).certificateVersion.getOrElse(""))
    assert(status1.upgradedVersions.configVersion   === TESTNODEMGMTPOLSTATUSES(1).configurationVersion.getOrElse(""))
    assert(status1.upgradedVersions.softwareVersion === TESTNODEMGMTPOLSTATUSES(1).softwareVersion.getOrElse(""))
  }
}
