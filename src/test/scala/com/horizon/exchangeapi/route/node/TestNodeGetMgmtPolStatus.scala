package com.horizon.exchangeapi.route.node

import com.horizon.exchangeapi.tables.{ManagementPoliciesTQ, ManagementPolicyRow, GetNMPStatusResponse, NMPStatus, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeGetMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestNodeGetMgmtPolStatus"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats = DefaultFormats // Brings in default date formats etc.
  val managementPolicy1 = "pol1"
  val managementPolicy2 = "pol2"
  //val orgManagementPolicy: String = authpref + managementPolicy


  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
      description = "",
      label = "",
      lastUpdated = ApiTime.nowUTC,
      orgId = "TestNodeGetMgmtPolStatus",
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
      managementPolicy = "TestNodeGetMgmtPolStatus/pol1",
      orgid = "TestNodeGetMgmtPolStatus",
      owner = "TestNodeGetMgmtPolStatus/u1",
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
        managementPolicy = "TestNodeGetMgmtPolStatus/pol2",
        orgid = "TestNodeGetMgmtPolStatus",
        owner = "TestNodeGetMgmtPolStatus/u1",
        patterns = "",
        properties = "",
        start = ""))

  private val TESTNODE: NodeRow =
    NodeRow(arch = "amd64",
      id = "TestNodeGetMgmtPolStatus/n1",
      heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
      lastHeartbeat = Some(ApiTime.nowUTC),
      lastUpdated = ApiTime.nowUTC,
      msgEndPoint = "messageEndpoint",
      name = "rpin1-normal",
      nodeType = "device",
      orgid = "TestNodeGetMgmtPolStatus",
      owner = "TestNodeGetMgmtPolStatus/u1",
      pattern = "TestNodeGetMgmtPolStatus/p1",
      publicKey = "key",
      regServices = """[{"url":"NodesSuiteTests/horizon.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/horizon.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
      softwareVersions = """{"horizon":"3.2.1"}""",
      token = "", // TestNodeGetMgmtPolStatus/n1:n1pw
      userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"horizon.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]""")

  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage = "pol1 description test",
      node = "TestNodeGetMgmtPolStatus/n1",
      policy = "TestNodeGetMgmtPolStatus/pol1",
      status = "Success",
      endTime = "",
      actualStartTime = ApiTime.nowUTC,
      scheduledStartTime = ApiTime.nowUTC,
      updated = ApiTime.nowUTC,
      certificateVersion = "",
      configurationVersion = "",
      softwareVersion = ""),
      NodeMgmtPolStatusRow(errorMessage = "pol2 description test",
        node = "TestNodeGetMgmtPolStatus/n1",
        policy = "TestNodeGetMgmtPolStatus/pol2",
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
      hashedPw = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeGetMgmtPolStatus/u1:u1pw
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestNodeGetMgmtPolStatus",
      updatedBy = "",
      username = "TestNodeGetMgmtPolStatus/u1")



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

  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol1 - as root") {
    fixtureNodeMgmtPol(
      _ => {
        fixtureNodeMgmtPolStatus(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus/" + managementPolicy1).method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("code: " + response.code + ", response.body: " + response.body)
            assert(response.code === HttpCode.OK.intValue)
            val resp: GetNMPStatusResponse = parse(response.body).extract[GetNMPStatusResponse]
            assert(resp.managementStatus.size == 1)
            assert(resp.managementStatus.contains("TestNodeGetMgmtPolStatus/" + managementPolicy1))
            var mp: NMPStatus = resp.managementStatus("TestNodeGetMgmtPolStatus/" + managementPolicy1)
            assert(mp.agentUpgradePolicyStatus.status === "Success")

          }, TESTNODEMGMTPOLSTATUSES)
      }, TESTMANAGEMENTPOLICY)
  }


  test("GET /orgs/TestNodeGetMgmtPolStatus/nodes/n1/managementStatus/pol2 - as root") {
    fixtureNodeMgmtPol(
      _ => {
        fixtureNodeMgmtPolStatus(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus/" + managementPolicy2).method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("code: " + response.code + ", response.body: " + response.body)
            assert(response.code === HttpCode.OK.intValue)
            val resp: GetNMPStatusResponse = parse(response.body).extract[GetNMPStatusResponse]
            assert(resp.managementStatus.size == 1)
            assert(resp.managementStatus.contains("TestNodeGetMgmtPolStatus/" + managementPolicy2))
            var mp: NMPStatus = resp.managementStatus("TestNodeGetMgmtPolStatus/" + managementPolicy2)
            assert(mp.agentUpgradePolicyStatus.status === "Fail")

          }, TESTNODEMGMTPOLSTATUSES)
      }, TESTMANAGEMENTPOLICY)
  }

}
