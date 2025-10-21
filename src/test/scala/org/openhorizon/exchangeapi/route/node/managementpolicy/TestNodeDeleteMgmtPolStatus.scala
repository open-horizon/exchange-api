package org.openhorizon.exchangeapi.route.node.managementpolicy

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.managementpolicy.{ManagementPoliciesTQ, ManagementPolicyRow}
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.{NodeMgmtPolStatusRow, NodeMgmtPolStatuses}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeDeleteMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
  
  private val TESTUSER: UserRow =
    UserRow(createdAt    = TIMESTAMP,
            isHubAdmin   = false,
            isOrgAdmin   = false,
            modifiedAt   = TIMESTAMP,
            organization = "TestNodeDeleteMgmtPolStatus",
            password     = Option(Password.hash("u1pw")),
            username     = "u1")
  private val TESTMANAGEMENTPOLICY: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(allowDowngrade   = false,
                            constraints      = "",
                            created          = ApiTime.nowUTC,
                            description      = "",
                            enabled          = true,
                            label            = "",
                            lastUpdated      = ApiTime.nowUTC,
                            manifest         = "",
                            managementPolicy = "TestNodeDeleteMgmtPolStatus/pol1",
                            orgid            = "TestNodeDeleteMgmtPolStatus",
                            owner            = TESTUSER.user,
                            patterns         = "",
                            properties       = "",
                            start            = ""),
        ManagementPolicyRow(allowDowngrade   = false,
                        constraints          = "",
                        created              = ApiTime.nowUTC,
                        description          = "",
                        enabled              = true,
                        label                = "",
                        lastUpdated          = ApiTime.nowUTC,
                        manifest             = "",
                        managementPolicy     = "TestNodeDeleteMgmtPolStatus/pol2",
                        orgid                = "TestNodeDeleteMgmtPolStatus",
                        owner                = TESTUSER.user,
                        patterns             = "",
                        properties           = "",
                        start                = ""))
  private val TESTNODE: NodeRow =
    NodeRow(arch               = "",
            id                 = "TestNodeDeleteMgmtPolStatus/n1",
            heartbeatIntervals = "",
            lastHeartbeat      = Option(ApiTime.nowUTC),
            lastUpdated        = ApiTime.nowUTC,
            msgEndPoint        = "",
            name               = "",
            nodeType           = "",
            orgid              = "TestNodeDeleteMgmtPolStatus",
            owner              = TESTUSER.user,
            pattern            = "TestNodeDeleteMgmtPolStatus/p1",
            publicKey          = "",
            regServices        = "",
            softwareVersions   = "",
            token              = "",
            userInput          = "")
  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage         = Option("pol1 description test"),
                             node                 = "TestNodeDeleteMgmtPolStatus/n1",
                             policy               = "TestNodeDeleteMgmtPolStatus/pol1",
                             status               = Option("Success"),
                             endTime              = Option(""),
                             actualStartTime      = Option(ApiTime.nowUTC),
                             scheduledStartTime   = ApiTime.nowUTC,
                             updated              = ApiTime.nowUTC,
                             certificateVersion   = Option(""),
                             configurationVersion = Option(""),
                             softwareVersion      = Option("")),
        NodeMgmtPolStatusRow(errorMessage         = Option("pol2 description test"),
                             node                 = "TestNodeDeleteMgmtPolStatus/n1",
                             policy               = "TestNodeDeleteMgmtPolStatus/pol2",
                             status               = Option("Fail"),
                             endTime              = Option(""),
                             actualStartTime      = Option(ApiTime.nowUTC),
                             scheduledStartTime   = ApiTime.nowUTC,
                             updated              = ApiTime.nowUTC,
                             certificateVersion   = Option(""),
                             configurationVersion = Option(""),
                             softwareVersion      = Option("")))
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestNodeDeleteMgmtPolStatus",
           orgType            = "",
           tags               = None,
           limits             = "")
  
  
  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ += TESTORGANIZATION) andThen
                                 (UsersTQ += TESTUSER) andThen
                                 (NodesTQ += TESTNODE) andThen
                                 (ManagementPoliciesTQ ++= TESTMANAGEMENTPOLICY) andThen
                                 (NodeMgmtPolStatuses ++= TESTNODEMGMTPOLSTATUSES)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodeDeleteMgmtPolStatus").delete andThen
                                 OrgsTQ.filter(_.orgid startsWith "TestNodeDeleteMgmtPolStatus").delete), AWAITDURATION)
  }
  
  // Management Policies that are dynamically needed, specific to the test case.
  def fixtureNodeMgmtPol(testCode: Seq[ManagementPolicyRow] => Any, testData: Seq[ManagementPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(ManagementPoliciesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(ManagementPoliciesTQ.filter(_.managementPolicy inSet testData.map(_.managementPolicy)).delete), AWAITDURATION)
  }
  
  def fixtureNodeMgmtPolStatus(testCode: Seq[NodeMgmtPolStatusRow] => Any, testData: Seq[NodeMgmtPolStatusRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodeMgmtPolStatuses ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(NodeMgmtPolStatuses.filter(_.policy inSet testData.map(_.policy)).delete), AWAITDURATION)
  }
  
  
  test("GET /orgs/TestNodeDeleteMgmtPolStatus-someorganization/nodes/n1/managementStatus/pol1 -- 404 Not Found - Organization") {
    val response: HttpResponse[String] = Http(URL + "TestNodeDeleteMgmtPolStatus-someorganization/nodes/n1/managementStatus/pol1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("GET /orgs/TestNodeDeleteMgmtPolStatus/nodes/somenode/managementStatus/pol1 -- 404 Not Found - Node") {
    val response: HttpResponse[String] = Http(URL + "TestNodeDeleteMgmtPolStatus/nodes/somenode/managementStatus/pol1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("GET /orgs/TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/someotherpolicy -- 404 Not Found - Management Policy") {
    val response: HttpResponse[String] = Http(URL + "TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/someotherpolicy").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("DELETE /orgs/TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/pol1 -- 204 Deleted - root") {
    val response: HttpResponse[String] = Http(URL + "TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/pol1").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NoContent.intValue)
    
    assert(Await.result(DBCONNECTION.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, "TestNodeDeleteMgmtPolStatus/pol1").result), AWAITDURATION).size === 0)
    assert(Await.result(DBCONNECTION.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, "TestNodeDeleteMgmtPolStatus/pol2").result), AWAITDURATION).size === 1)
  }
}