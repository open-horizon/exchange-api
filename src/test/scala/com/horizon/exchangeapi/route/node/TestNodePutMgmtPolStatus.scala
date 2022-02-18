package com.horizon.exchangeapi.route.node

import com.horizon.exchangeapi
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, NodeMangementPolicyStatus, PutNodeMgmtPolStatusRequest, Role, TestDBConnection, UpgradedVersions}
import com.horizon.exchangeapi.tables.{BusinessPoliciesTQ, BusinessPolicyRow, ManagementPolicies, ManagementPoliciesTQ, ManagementPolicyRow, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ, UserRow, UsersTQ}
import org.checkerframework.checker.units.qual.s
import org.json4s.DefaultFormats
import org.json4s.native.Serialization.write
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.parsing.json

class TestNodePutMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePutMgmtPolStatus" + "/" + "a1" + ":" + "a1pw"))
  private val AWAITDURATION: Duration = 15.seconds
  private val CONTENT: (String, String) = ("Content-Type","application/json")
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePutMgmtPolStatus" + "/" + "n1" + ":" + "n1pw"))
  // private val ORGID = "TestNodePutMgmtPolStatus"
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode("TestNodePutMgmtPolStatus" + "/" + "u1" + ":" + "u1pw"))
  
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  // Test data.
  private val TESTNODE: NodeRow =
    NodeRow(arch = "amd64",
            id = "TestNodePutMgmtPolStatus/n1",
            heartbeatIntervals = "",
            lastHeartbeat = Option(ApiTime.nowUTC),
            lastUpdated = ApiTime.nowUTC,
            msgEndPoint = "",
            name = "",
            nodeType = "device",
            orgid = "TestNodePutMgmtPolStatus",
            owner = "TestNodePutMgmtPolStatus/u1",
            pattern = "",
            publicKey = "",
            regServices = "",
            softwareVersions = "",
            token = "",
            userInput = "")
  private val TESTMANAGEMENTPOLICY: ManagementPolicyRow =
    ManagementPolicyRow(allowDowngrade   = false,
                        constraints      = "",
                        created          = ApiTime.nowUTC,
                        description      = "",
                        enabled          = true,
                        label            = "",
                        lastUpdated      = ApiTime.nowUTC,
                        manifest         = "",
                        managementPolicy = "TestNodePutMgmtPolStatus/pol1",
                        orgid            = "TestNodePutMgmtPolStatus",
                        owner            = "TestNodePutMgmtPolStatus/u1",
                        patterns         = "",
                        properties       = "",
                        start            = "",
                        startWindow      = 0)
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
           description        = "",
           label              = "",
           lastUpdated        = ApiTime.nowUTC,
           orgId              = "TestNodePutMgmtPolStatus",
           orgType            = "",
           tags               = None,
           limits             = "")
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
            hubAdmin    = false,
            email       = "",
            hashedPw    = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodePutMgmtPolStatus/u1:u1pw
            lastUpdated = ApiTime.nowUTC,
            orgid       = "TestNodePutMgmtPolStatus",
            updatedBy   = "",
            username    = "TestNodePutMgmtPolStatus/u1")
  
  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ += TESTORGANIZATION) andThen
                                       (UsersTQ += TESTUSER) andThen
                                       (NodesTQ += TESTNODE) andThen
                                       (ManagementPoliciesTQ += TESTMANAGEMENTPOLICY)), AWAITDURATION)
  }
  
  // Teardown test harness.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodePutMgmtPolStatus").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestNodePutMgmtPolStatus").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  test("PUT /orgs/" + TESTORGANIZATION.orgId + "/nodes/n1/managementStatus/pol1 - Default") {
    val requestBody: PutNodeMgmtPolStatusRequest =
      PutNodeMgmtPolStatusRequest(
        agentUpgradePolicyStatus =
          NodeMangementPolicyStatus(scheduledTime = ApiTime.nowUTC,
                                    startTime = ApiTime.nowUTC,
                                    endTime = ApiTime.nowUTC,
                                    upgradedVersions =
                                      UpgradedVersions(softwareVersion = "1.0.0",
                                                       certVersion = "2.0.0",
                                                       configVersion = "3.0.0"),
                                    status = "failed",
                                    errorMessage = "some error message"))
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATION.orgId + "/nodes/" + "n1" + "/managementStatus/" + "pol1").postData(write(requestBody)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.PUT_OK.intValue)
    
    val status: Seq[NodeMgmtPolStatusRow] = Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, TESTMANAGEMENTPOLICY.managementPolicy).result), AWAITDURATION)
    
    assert(status.length === 1)
    
    assert(status.head.actualStartTime      === requestBody.agentUpgradePolicyStatus.startTime)
    assert(status.head.certificateVersion   === requestBody.agentUpgradePolicyStatus.upgradedVersions.certVersion)
    assert(status.head.configurationVersion === requestBody.agentUpgradePolicyStatus.upgradedVersions.configVersion)
    assert(status.head.endTime              === requestBody.agentUpgradePolicyStatus.endTime)
    assert(status.head.errorMessage         === requestBody.agentUpgradePolicyStatus.errorMessage)
    assert(status.head.node                 === TESTNODE.id)
    assert(status.head.policy               === TESTMANAGEMENTPOLICY.managementPolicy)
    assert(status.head.scheduledStartTime   === requestBody.agentUpgradePolicyStatus.scheduledTime)
    assert(status.head.softwareVersion      === requestBody.agentUpgradePolicyStatus.upgradedVersions.softwareVersion)
    assert(status.head.updated.isEmpty      === false)
  
    val change: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestNodePutMgmtPolStatus").result), AWAITDURATION)
    
    assert(change.length === 1)
    
    assert(change.head.id        === "n1")
    assert(change.head.category  === ResChangeCategory.NODE.toString)
    assert(0           <= change.head.changeId)
    assert(0L          <= change.head.lastUpdated.getTime)
    assert(change.head.orgId     === TESTORGANIZATION.orgId)
    assert(change.head.operation === ResChangeOperation.CREATEDMODIFIED.toString)
    assert(change.head.public    === "false")
    assert(change.head.resource  === ResChangeResource.NODEMGMTPOLSTATUS.toString)
  
    Await.ready(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.filter(_.node === TESTNODE.id).delete andThen
                                       ResourceChangesTQ.filter(_.orgId === TESTORGANIZATION.orgId).delete), AWAITDURATION)
  }
  
  test("PUT /orgs/" + TESTORGANIZATION.orgId + "/nodes/n1/managementStatus/pol1 - Update") {
    val mgmtPolStatus: NodeMgmtPolStatusRow =
      NodeMgmtPolStatusRow(actualStartTime = ApiTime.pastUTC(120),
                           certificateVersion   = "0.0.1",
                           configurationVersion = "0.0.2",
                           endTime              = ApiTime.pastUTC(120),
                           errorMessage         = "an error message",
                           node                 = TESTNODE.id,
                           policy               = TESTMANAGEMENTPOLICY.managementPolicy,
                           scheduledStartTime   = ApiTime.pastUTC(120),
                           softwareVersion      = "0.0.3",
                           status               = "in progress",
                           updated              = ApiTime.pastUTC(120))
    
    Await.ready(DBCONNECTION.getDb.run(NodeMgmtPolStatuses += mgmtPolStatus), AWAITDURATION)
  
    val requestBody: PutNodeMgmtPolStatusRequest =
      PutNodeMgmtPolStatusRequest(
        agentUpgradePolicyStatus =
          NodeMangementPolicyStatus(scheduledTime = ApiTime.nowUTC,
            startTime = ApiTime.nowUTC,
            endTime = ApiTime.nowUTC,
            upgradedVersions =
              UpgradedVersions(softwareVersion = "1.0.0",
                certVersion = "2.0.0",
                configVersion = "3.0.0"),
            status = "failed",
            errorMessage = "some error message"))
    val response: HttpResponse[String] = Http(URL + TESTORGANIZATION.orgId + "/nodes/" + "n1" + "/managementStatus/" + "pol1").postData(write(requestBody)).method("put").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.PUT_OK.intValue)
    
    val status: Seq[NodeMgmtPolStatusRow] = Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, TESTMANAGEMENTPOLICY.managementPolicy).result), AWAITDURATION)
    
    assert(status.length === 1)
    
    assert(status.head.actualStartTime      === requestBody.agentUpgradePolicyStatus.startTime)
    assert(status.head.certificateVersion   === requestBody.agentUpgradePolicyStatus.upgradedVersions.certVersion)
    assert(status.head.configurationVersion === requestBody.agentUpgradePolicyStatus.upgradedVersions.configVersion)
    assert(status.head.endTime              === requestBody.agentUpgradePolicyStatus.endTime)
    assert(status.head.errorMessage         === requestBody.agentUpgradePolicyStatus.errorMessage)
    assert(status.head.node                 === TESTNODE.id)
    assert(status.head.policy               === TESTMANAGEMENTPOLICY.managementPolicy)
    assert(status.head.scheduledStartTime   === requestBody.agentUpgradePolicyStatus.scheduledTime)
    assert(status.head.softwareVersion      === requestBody.agentUpgradePolicyStatus.upgradedVersions.softwareVersion)
    assert(status.head.updated.isEmpty      === false)
    
    val change: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestNodePutMgmtPolStatus").result), AWAITDURATION)
    
    assert(change.length === 1)
    
    assert(change.head.id        === "n1")
    assert(change.head.category  === ResChangeCategory.NODE.toString)
    assert(0           <= change.head.changeId)
    assert(0L          <= change.head.lastUpdated.getTime)
    assert(change.head.orgId     === TESTORGANIZATION.orgId)
    assert(change.head.operation === ResChangeOperation.CREATEDMODIFIED.toString)
    assert(change.head.public    === "false")
    assert(change.head.resource  === ResChangeResource.NODEMGMTPOLSTATUS.toString)
  }
}
