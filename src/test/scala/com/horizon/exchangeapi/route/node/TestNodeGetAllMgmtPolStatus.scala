package com.horizon.exchangeapi.route.node

import com.horizon.exchangeapi.tables.{ManagementPoliciesTQ, ManagementPolicyRow, GetNMPStatusResponse, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.Http
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeGetAllMgmtPolStatus extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestNodeGetAllMgmtPolStatus"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats = DefaultFormats // Brings in default date formats etc.

  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
      description = "",
      label = "",
      lastUpdated = ApiTime.nowUTC,
      orgId = "TestNodeGetAllMgmtPolStatus",
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
      managementPolicy = "TestNodeGetAllMgmtPolStatus/pol1",
      orgid = "TestNodeGetAllMgmtPolStatus",
      owner = "TestNodeGetAllMgmtPolStatus/u1",
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
        managementPolicy = "TestNodeGetAllMgmtPolStatus/pol2",
        orgid = "TestNodeGetAllMgmtPolStatus",
        owner = "TestNodeGetAllMgmtPolStatus/u1",
        patterns = "",
        properties = "",
        start = ""))

  private val TESTNODE: NodeRow =
    NodeRow(arch = "amd64",
      id = "TestNodeGetAllMgmtPolStatus/n1",
      heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
      lastHeartbeat = Some(ApiTime.nowUTC),
      lastUpdated = ApiTime.nowUTC,
      msgEndPoint = "messageEndpoint",
      name = "rpin1-normal",
      nodeType = "device",
      orgid = "TestNodeGetAllMgmtPolStatus",
      owner = "TestNodeGetAllMgmtPolStatus/u1",
      pattern = "TestNodeGetAllMgmtPolStatus/p1",
      publicKey = "key",
      regServices = """[{"url":"NodesSuiteTests/horizon.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/horizon.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
      softwareVersions = """{"horizon":"3.2.1"}""",
      token = "", // TestNodeGetAllMgmtPolStatus/n1:n1pw
      userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"horizon.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]""")

  private val TESTNODEMGMTPOLSTATUSES: Seq[NodeMgmtPolStatusRow] =
    Seq(NodeMgmtPolStatusRow(errorMessage = "pol1 description test",
      node = "TestNodeGetAllMgmtPolStatus/n1",
      policy = "TestNodeGetAllMgmtPolStatus/pol1",
      status = "Success",
      endTime = "",
      actualStartTime = ApiTime.nowUTC,
      scheduledStartTime = ApiTime.nowUTC,
      updated = ApiTime.nowUTC,
      certificateVersion = "",
      configurationVersion = "",
      softwareVersion = ""),
      NodeMgmtPolStatusRow(errorMessage = "pol2 description test",
        node = "TestNodeGetAllMgmtPolStatus/n1",
        policy = "TestNodeGetAllMgmtPolStatus/pol2",
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
      hashedPw = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeGetAllMgmtPolStatus/u1:u1pw
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestNodeGetAllMgmtPolStatus",
      updatedBy = "",
      username = "TestNodeGetAllMgmtPolStatus/u1")



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


  test("GET /orgs/TestNodeGetAllMgmtPolStatus/nodes/n1/managementStatus - Default") {
    fixtureNodeMgmtPol(
      _ => {
        fixtureNodeMgmtPolStatus(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("code: " + response.code + ", response.body: " + response.body)
            assert(response.code === HttpCode.OK.intValue)
            val resp: GetNMPStatusResponse = parse(response.body).extract[GetNMPStatusResponse]
            assert(resp.managementStatus.size == 2)

          }, TESTNODEMGMTPOLSTATUSES)
      }, TESTMANAGEMENTPOLICY)
  }



}
