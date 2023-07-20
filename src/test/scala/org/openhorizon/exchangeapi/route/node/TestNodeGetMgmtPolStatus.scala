package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.{ManagementPoliciesTQ, ManagementPolicyRow}
import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.{GetNMPStatusResponse, NMPStatus, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, PolicyStatus}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.{ManagementPoliciesTQ, ManagementPolicyRow}
import org.openhorizon.exchangeapi.{Role, TestDBConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeGetMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  val managementPolicy1: String = "pol1"
  val managementPolicy2: String = "pol2"
  
  
  private val TESTMANAGEMENTPOLICY: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "",
                            enabled          = true,
                            label            = "",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "",
                            managementPolicy = "TestNodeGetMgmtPolStatus/pol1",
                            orgid            = "TestNodeGetMgmtPolStatus",
                            owner            = "TestNodeGetMgmtPolStatus/u1",
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
                            managementPolicy = "TestNodeGetMgmtPolStatus/pol2",
                            orgid            = "TestNodeGetMgmtPolStatus",
                            owner            = "TestNodeGetMgmtPolStatus/u1",
                            patterns         = "",
                            properties       = "",
                            start            = ""))
  private val TESTNODE: NodeRow =
    NodeRow(arch               = "",
            id                 = "TestNodeGetMgmtPolStatus/n1",
            heartbeatIntervals = "",
            lastHeartbeat      = Option(ApiTime.nowUTC),
            lastUpdated        = ApiTime.nowUTC,
            msgEndPoint        = "",
            name               = "",
            nodeType           = "",
            orgid              = "TestNodeGetMgmtPolStatus",
            owner              = "TestNodeGetMgmtPolStatus/u1",
            pattern            = "",
            publicKey          = "",
            regServices        = "",
            softwareVersions   = "",
            token              = "",
            userInput          = "")
  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage         = Option("pol1 description test"),
                             node                 = "TestNodeGetMgmtPolStatus/n1",
                             policy               = "TestNodeGetMgmtPolStatus/pol1",
                             status               = Option("Success"),
                             endTime              = Option(""),
                             actualStartTime      = Option(ApiTime.nowUTC),
                             scheduledStartTime   = ApiTime.nowUTC,
                             updated              = ApiTime.nowUTC,
                             certificateVersion   = Option(""),
                             configurationVersion = Option(""),
                             softwareVersion      = Option("")),
        NodeMgmtPolStatusRow(errorMessage         = None,
                             node                 = "TestNodeGetMgmtPolStatus/n1",
                             policy               = "TestNodeGetMgmtPolStatus/pol2",
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
           orgId              = "TestNodeGetMgmtPolStatus",
           orgType            = "",
           tags               = None,
           limits             = "")
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
            hubAdmin    = false,
            email       = "",
            hashedPw    = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeGetMgmtPolStatus/u1:u1pw
            lastUpdated = ApiTime.nowUTC,
            orgid       = "TestNodeGetMgmtPolStatus",
            updatedBy   = "",
            username    = "TestNodeGetMgmtPolStatus/u1")
  
  
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
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodeGetMgmtPolStatus").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodeGetMgmtPolStatus").delete), AWAITDURATION)

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

  def fixtureNodeMgmtPolStatus(testCode: Seq[NodeMgmtPolStatusRow] => Any, testData: Seq[NodeMgmtPolStatusRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.filter(_.policy inSet testData.map(_.policy)).delete), AWAITDURATION)
  }
  
  test("GET /orgs/TestNodeGetMgmtPolStatus-someorganization/nodes/n1/managementStatus/pol1 -- 404 Not Found - Organization") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetMgmtPolStatus-someorganization/nodes/n1/managementStatus/pol1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/somenode/managementStatus/pol1 -- 404 Not Found - Node") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetMgmtPolStatus/nodes/somenode/managementStatus/pol1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/someotherpolicy -- 404 Not Found - Management Policy") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/someotherpolicy").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol1 -- 200 Ok - root") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val resp: Map[String, NMPStatus] = parse(response.body).extract[GetNMPStatusResponse].managementStatus
    
    assert(resp.size === 1)
    assert(resp.contains(TESTNODEMGMTPOLSTATUSES.head.policy))
    
    val status: PolicyStatus = resp(TESTNODEMGMTPOLSTATUSES.head.policy).agentUpgradePolicyStatus
    
    assert(status.endTime       === TESTNODEMGMTPOLSTATUSES.head.endTime.get)
    assert(status.errorMessage  === TESTNODEMGMTPOLSTATUSES.head.errorMessage.get)
    assert(status.lastUpdated   === TESTNODEMGMTPOLSTATUSES.head.updated)
    assert(status.scheduledTime === TESTNODEMGMTPOLSTATUSES.head.scheduledStartTime)
    assert(status.startTime     === TESTNODEMGMTPOLSTATUSES.head.actualStartTime.get)
    assert(status.status        === TESTNODEMGMTPOLSTATUSES.head.status.get)
    assert(status.upgradedVersions.certVersion     === TESTNODEMGMTPOLSTATUSES.head.certificateVersion.get)
    assert(status.upgradedVersions.configVersion   === TESTNODEMGMTPOLSTATUSES.head.configurationVersion.get)
    assert(status.upgradedVersions.softwareVersion === TESTNODEMGMTPOLSTATUSES.head.softwareVersion.get)
  }

  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol2 -- 200 Ok - root - None") {
    val response: HttpResponse[String] = Http(URL + "TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol2").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  
    assert(response.code === HttpCode.OK.intValue)
  
    val resp: Map[String, NMPStatus] = parse(response.body).extract[GetNMPStatusResponse].managementStatus
  
    assert(resp.size === 1)
    assert(resp.contains(TESTNODEMGMTPOLSTATUSES(1).policy))
  
    val status: PolicyStatus = resp(TESTNODEMGMTPOLSTATUSES(1).policy).agentUpgradePolicyStatus
  
    assert(status.endTime       === TESTNODEMGMTPOLSTATUSES(1).endTime.getOrElse(""))
    assert(status.errorMessage  === TESTNODEMGMTPOLSTATUSES(1).errorMessage.getOrElse(""))
    assert(status.lastUpdated   === TESTNODEMGMTPOLSTATUSES(1).updated)
    assert(status.scheduledTime === TESTNODEMGMTPOLSTATUSES(1).scheduledStartTime)
    assert(status.startTime     === TESTNODEMGMTPOLSTATUSES(1).actualStartTime.getOrElse(""))
    assert(status.status        === TESTNODEMGMTPOLSTATUSES(1).status.getOrElse(""))
    assert(status.upgradedVersions.certVersion     === TESTNODEMGMTPOLSTATUSES(1).certificateVersion.getOrElse(""))
    assert(status.upgradedVersions.configVersion   === TESTNODEMGMTPOLSTATUSES(1).configurationVersion.getOrElse(""))
    assert(status.upgradedVersions.softwareVersion === TESTNODEMGMTPOLSTATUSES(1).softwareVersion.getOrElse(""))
  }
}
