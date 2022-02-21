package com.horizon.exchangeapi.route.node

import com.horizon.exchangeapi.tables.{ManagementPoliciesTQ, ManagementPolicyRow, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeDeleteMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestNodeDeleteMgmtPolStatus"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats = DefaultFormats // Brings in default date formats etc.

  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
      description = "",
      label = "",
      lastUpdated = ApiTime.nowUTC,
      orgId = "TestNodeDeleteMgmtPolStatus",
      orgType = "",
      tags = None,
      limits = "")
  private val TESTMANAGEMENTPOLICY: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(allowDowngrade = false,
      constraints = "",
      created = ApiTime.nowUTC,
      description = "",
      enabled = true,
      label = "",
      lastUpdated = ApiTime.nowUTC,
      manifest = "",
      managementPolicy = "TestNodeDeleteMgmtPolStatus/pol1",
      orgid = "TestNodeDeleteMgmtPolStatus",
      owner = "TestNodeDeleteMgmtPolStatus/u1",
      patterns = "",
      properties = "",
      start = ""),
    ManagementPolicyRow(allowDowngrade = false,
      constraints = "",
      created = ApiTime.nowUTC,
      description = "",
      enabled = true,
      label = "",
      lastUpdated = ApiTime.nowUTC,
      manifest = "",
      managementPolicy = "TestNodeDeleteMgmtPolStatus/pol2",
      orgid = "TestNodeDeleteMgmtPolStatus",
      owner = "TestNodeDeleteMgmtPolStatus/u1",
      patterns = "",
      properties = "",
      start = ""))

  private val TESTNODE: NodeRow =
    NodeRow(arch = "amd64",
      id = "TestNodeDeleteMgmtPolStatus/n1",
      heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
      lastHeartbeat = Some(ApiTime.nowUTC),
      lastUpdated = ApiTime.nowUTC,
      msgEndPoint = "messageEndpoint",
      name = "rpin1-normal",
      nodeType = "device",
      orgid = "TestNodeDeleteMgmtPolStatus",
      owner = "TestNodeDeleteMgmtPolStatus/u1",
      pattern = "TestNodeDeleteMgmtPolStatus/p1",
      publicKey = "key",
      regServices = """[{"url":"NodesSuiteTests/horizon.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/horizon.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
      softwareVersions = """{"horizon":"3.2.1"}""",
      token = "", // TestNodeDeleteMgmtPolStatus/n1:n1pw
      userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"horizon.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]""")

  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage = "pol1 description test",
      node = "TestNodeDeleteMgmtPolStatus/n1",
      policy = "TestNodeDeleteMgmtPolStatus/pol1",
      status = "Success",
      endTime = "",
      actualStartTime = ApiTime.nowUTC,
      scheduledStartTime = ApiTime.nowUTC,
      updated = ApiTime.nowUTC,
      certificateVersion = "",
      configurationVersion = "",
      softwareVersion = ""),
      NodeMgmtPolStatusRow(errorMessage = "pol2 description test",
        node = "TestNodeDeleteMgmtPolStatus/n1",
        policy = "TestNodeDeleteMgmtPolStatus/pol2",
        status = "Fail",
        endTime = "",
        actualStartTime = ApiTime.nowUTC,
        scheduledStartTime = ApiTime.nowUTC,
        updated = ApiTime.nowUTC,
        certificateVersion = "",
        configurationVersion = "",
        softwareVersion = ""))

  private val TESTUSER: UserRow =
    UserRow(admin = false,
      hubAdmin = false,
      email = "",
      hashedPw = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeDeleteMgmtPolStatus/u1:u1pw
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestNodeDeleteMgmtPolStatus",
      updatedBy = "",
      username = "TestNodeDeleteMgmtPolStatus/u1")



  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ += TESTORGANIZATION) andThen
      (UsersTQ += TESTUSER) andThen
      (NodesTQ += TESTNODE) andThen
      (NodeMgmtPolStatuses ++= TESTNODEMGMTPOLSTATUSES) andThen
      (ManagementPoliciesTQ ++= TESTMANAGEMENTPOLICY)), AWAITDURATION)
  }

  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestNodeDeleteMgmtPolStatus").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestNodeDeleteMgmtPolStatus").delete), AWAITDURATION)

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

  test("DELETE /orgs/TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/pol1 - Default") {
    fixtureNodeMgmtPol(
      _ => {
        fixtureNodeMgmtPolStatus(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus/pol1").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("Code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.DELETED.intValue)

            val status: Seq[NodeMgmtPolStatusRow] = Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, "TestNodeDeleteMgmtPolStatus/pol1").result), AWAITDURATION)
            assert(status.length === 0)

          }, TESTNODEMGMTPOLSTATUSES)
      }, TESTMANAGEMENTPOLICY)
  }


  test("DELETE /orgs/TestNodeDeleteMgmtPolStatus/nodes/n1/managementStatus/pol2 - Default") {
    fixtureNodeMgmtPol(
      _ => {
        fixtureNodeMgmtPolStatus(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus/pol2").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("Code: " + response.code)
            info("Body: " + response.body)
            assert(response.code === HttpCode.DELETED.intValue)

            val status: Seq[NodeMgmtPolStatusRow] = Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(TESTNODE.id, "TestNodeDeleteMgmtPolStatus/pol2").result), AWAITDURATION)
            assert(status.length === 0)

          }, TESTNODEMGMTPOLSTATUSES)
      }, TESTMANAGEMENTPOLICY)
  }




}