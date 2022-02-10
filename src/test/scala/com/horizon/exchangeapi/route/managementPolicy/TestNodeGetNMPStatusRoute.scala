package com.horizon.exchangeapi.route.managementPolicy

import com.horizon.exchangeapi.tables.{NodeMgmtPolStatusRow, NodeMgmtPolStatuses, ManagementPolicies, ManagementPoliciesTQ, ManagementPolicy, ManagementPolicyRow, NodeErrorRow, NodeErrorTQ, NodeHeartbeatIntervals, NodePolicy, NodePolicyRow, NodePolicyTQ, NodeRow, NodeStatusRow, NodeStatusTQ, NodesTQ, OneProperty, OneService, OneUserInputService, OrgRow, OrgsTQ, RegService, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, NodeDetails, Role, StrConstants, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable.{List, Map}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestNodeGetNMPStatusRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/" + "TestNodeGetNMPStatusRoute"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats = DefaultFormats // Brings in default date formats etc.

  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
      description = "",
      label = "",
      lastUpdated = ApiTime.nowUTC,
      orgId = "TestNodeGetNMPStatusRoute",
      orgType = "",
      tags = None,
      limits = "")

  private val TESTNODEMGMTPOLICY: Seq[ManagementPolicyRow] =
    Seq(ManagementPolicyRow(managementPolicy = "TestNodeGetNMPStatusRoute/nmp1", //nmpid
      orgid = "TestNodeGetNMPStatusRoute",
      owner = "root/root",
      label = "",
      description = "FIRST",
      properties = "",
      constraints = "",
      patterns = "",
      enabled = true,
      agentUpgradePolicy = """{"atLeastVersion": "current", "start": "now", "duration": 0}""",
      lastUpdated = ApiTime.nowUTC,
      created = ApiTime.nowUTC), // replace boolean and data types with actual values like true false etc
      ManagementPolicyRow(
        managementPolicy = "TestNodeGetNMPStatusRoute/nmp2", //nmpid
        orgid = "TestNodeGetNMPStatusRoute",
        owner = "root/root",
        label = "",
        description = "SECOND",
        properties = "",
        constraints = "",
        patterns = "",
        enabled = true,
        agentUpgradePolicy = """{"atLeastVersion": "current", "start": "now", "duration": 0}""",
        lastUpdated = ApiTime.nowUTC,
        created = ApiTime.nowUTC)) //

  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch = "amd64",
      id = "TestNodeGetNMPStatusRoute/n1",
      heartbeatIntervals = """{"minInterval":6,"maxInterval":15,"intervalAdjustment":2}""",
      lastHeartbeat = Some(ApiTime.nowUTC),
      lastUpdated = ApiTime.nowUTC,
      msgEndPoint = "messageEndpoint",
      name = "rpin1-normal",
      nodeType = "device",
      orgid = "TestNodeGetNMPStatusRoute",
      owner = "root/root",
      pattern = "TestNodeGetNMPStatusRoute/p1",
      publicKey = "key",
      regServices = """[{"url":"NodesSuiteTests/horizon.sdr","numAgreements":1,"configState":"active","policy":"{json policy for n1 sdr}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"memory","value":"300","propType":"int","op":">="},{"name":"version","value":"1.0.0","propType":"version","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"dataVerification","value":"true","propType":"boolean","op":"="}]},{"url":"NodesSuiteTests/horizon.netspeed","numAgreements":1,"configState":"active","policy":"{json policy for n1 netspeed}","properties":[{"name":"arch","value":"arm","propType":"string","op":"in"},{"name":"agreementProtocols","value":"ExchangeAutomatedTest","propType":"list","op":"in"},{"name":"version","value":"1.0.0","propType":"version","op":"in"}]}]""",
      softwareVersions = """{"horizon":"3.2.1"}""",
      token = "", // TestNodeGetNMPStatusRoute/n1:n1pw
      userInput = """[{"serviceOrgid":"NodesSuiteTests","serviceUrl":"horizon.sdr","serviceArch":"amd64","serviceVersionRange":"[0.0.0,INFINITY)","inputs":[{"name":"UI_STRING","value":"mystr - updated"},{"name":"UI_INT","value":5},{"name":"UI_BOOLEAN","value":true}]}]"""))



  // Begin building testing harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ.rows += TESTORGANIZATION) andThen
      (NodesTQ.rows ++= TESTNODES)// andThen
      //(ManagementPoliciesTQ.rows ++= TESTNODEMGMTPOLICY)
      //(AgbotsTQ.rows += TESTAGBOT) andThen
      //(ServicesTQ.rows ++= TESTSERVICES)
    ), AWAITDURATION)
  }

  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.rows.filter(_.orgId startsWith "TestNodeGetNMPStatusRoute").delete andThen
      OrgsTQ.rows.filter(_.orgid startsWith "TestNodeGetNMPStatusRoute").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }

  // Management Policies that are dynamically needed, specific to the test case.
  def fixtureNMP(testCode: Seq[ManagementPolicyRow] => Any, testData: Seq[ManagementPolicyRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(ManagementPoliciesTQ.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(ManagementPoliciesTQ.rows.filter(_.managementPolicy inSet testData.map(_.managementPolicy)).delete), AWAITDURATION)
  }

  def statusNMP(testCode: Seq[NodeMgmtPolStatusRow] => Any, testData: Seq[NodeMgmtPolStatusRow]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.rows ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeMgmtPolStatuses.rows.filter(_.policy inSet testData.map(_.policy)).delete), AWAITDURATION)
  }


  test("GET /orgs/TestNodeGetNMPStatusRoute/nodes/n1 - as root") {
        val response = Http(URL+"/nodes/n1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: "+response.code+", response.body: "+response.body)
        assert(response.code === HttpCode.OK.intValue)
  }



  test("GET /orgs/TestNodeGetNMPStatusRoute/nodes/n1/managementStatus - as root") {
    val NMPstatus: Seq[NodeMgmtPolStatusRow] =
      Seq(NodeMgmtPolStatusRow(
        errorMessage = "nmp1 description test",
        node = "TestNodeGetNMPStatusRoute/n1",
        policy = "TestNodeGetNMPStatusRoute/nmp1",
        status = "Success",
        endTime = "",
        actualStartTime = ApiTime.nowUTC,
        scheduledStartTime = ApiTime.nowUTC,
        updated = ApiTime.nowUTC,
        certificateVersion = "",
        configurationVersion = "",
        softwareVersion = ""), // replace boolean and data types with actual values like true false etc
        NodeMgmtPolStatusRow(
          errorMessage = "nmp2 description test",
          node = "TestNodeGetNMPStatusRoute/n1",
          policy = "TestNodeGetNMPStatusRoute/nmp2",
          status = "Fail",
          endTime = "",
          actualStartTime = ApiTime.nowUTC,
          scheduledStartTime = ApiTime.nowUTC,
          updated = ApiTime.nowUTC,
          certificateVersion = "",
          configurationVersion = "",
          softwareVersion = "")) //

    fixtureNMP(
      _ => {
        statusNMP(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("code: " + response.code + ", response.body: " + response.body)
            assert(response.code === HttpCode.OK.intValue)
          }, NMPstatus)
      }, TESTNODEMGMTPOLICY)
  }

  test("GET /orgs/TestNodeGetNMPStatusRoute/nodes/n1/managementStatus/nmp2 - as root") {
    val NMPstatus: Seq[NodeMgmtPolStatusRow] =
      Seq(NodeMgmtPolStatusRow(
        errorMessage = "nmp1 description test",
        node = "TestNodeGetNMPStatusRoute/n1",
        policy = "TestNodeGetNMPStatusRoute/nmp1",
        status = "Success",
        endTime = "",
        actualStartTime = ApiTime.nowUTC,
        scheduledStartTime = ApiTime.nowUTC,
        updated = ApiTime.nowUTC,
        certificateVersion = "",
        configurationVersion = "",
        softwareVersion = ""), // replace boolean and data types with actual values like true false etc
        NodeMgmtPolStatusRow(
          errorMessage = "nmp2 description test",
          node = "TestNodeGetNMPStatusRoute/n1",
          policy = "TestNodeGetNMPStatusRoute/nmp2",
          status = "Fail",
          endTime = "",
          actualStartTime = ApiTime.nowUTC,
          scheduledStartTime = ApiTime.nowUTC,
          updated = ApiTime.nowUTC,
          certificateVersion = "",
          configurationVersion = "",
          softwareVersion = "")) ////


    fixtureNMP(
      _ => {
        statusNMP(
          _ => {
            val response = Http(URL + "/nodes/n1/managementStatus/nmp1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
            info("code: " + response.code + ", response.body: " + response.body)
            assert(response.code === HttpCode.OK.intValue)
          }, NMPstatus)
      }, TESTNODEMGMTPOLICY)
  }

  test("GET /orgs/TestNodeGetNMPStatusRoute/managementpolicies - as root") {

    fixtureNMP(
      _ => {
        val response = Http(URL+"/managementpolicies").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("code: "+response.code+", response.body: "+response.body)
        assert(response.code === HttpCode.OK.intValue)
        val getResp = parse(response.body)
        assert(getResp != "")//getResp.agentUpgradePolicy.duration === 0)
      }, TESTNODEMGMTPOLICY)
  }

//  test("DELETE /orgs/TestNodeGetNMPStatusRoute/nodes/n1/managementStatus/nmp2 - as root") {
//    val TestNMP: Seq[ManagementPolicyRow] =
//      Seq(ManagementPolicyRow(
//        managementPolicy = "nmp1", //nmpid
//        orgid = "TestNodeGetNMPStatusRoute",
//        owner = "root/root",
//        label = "",
//        description = "nmpid1 description test",
//        properties = "",
//        constraints = "",
//        patterns = "",
//        enabled = true,
//        agentUpgradePolicy = """{"atLeastVersion": "current", "start": "now", "duration": 0}""",
//        lastUpdated = ApiTime.nowUTC,
//        created = ApiTime.nowUTC), // replace boolean and data types with actual values like true false etc
//        ManagementPolicyRow(
//          managementPolicy = "nmp2", //nmpid
//          orgid = "TestNodeGetNMPStatusRoute",
//          owner = "root/root",
//          label = "",
//          description = "nmpid2 description test",
//          properties = "",
//          constraints = "",
//          patterns = "",
//          enabled = true,
//          agentUpgradePolicy = """{"atLeastVersion": "current", "start": "now", "duration": 0}""",
//          lastUpdated = ApiTime.nowUTC,
//          created = ApiTime.nowUTC)) //
//
//
//    fixtureNMP(
//      _ => {
//        val response = Http(URL+"/nodes/n1/managementStatus/nmp2").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
//        info("code: "+response.code+", response.body: "+response.body)
//        assert(response.code === HttpCode.DELETED.intValue)
//      }, TestNMP)
//  }


  ignore("GET /orgs/TestNodeGetNMPStatusRoute/nodes/n1/testmanagementStatus - as root") {
    assert(true)
  }
}



//Seq(NodeMgmtPolStatusRow(
//actualStartTime = ApiTime.nowUTC,
//certificateVersion = "",
//configurationVersion = "",
//endTime = "",
//errorMessage = "nmp1 description test",
//node = "n1",
//policy = "nmp1",
//scheduledStartTime = ApiTime.nowUTC,
//softwareVersion = "",
//status = "Success",
//updated = ApiTime.nowUTC), // replace boolean and data types with actual values like true false etc
//NodeMgmtPolStatusRow(
//actualStartTime = ApiTime.nowUTC,
//certificateVersion = "",
//configurationVersion = "",
//endTime = "",
//errorMessage = "nmp2 description test",
//node = "n1",
//policy = "nmp2",
//scheduledStartTime = ApiTime.nowUTC,
//softwareVersion = "",
//status = "Success",
//updated = ApiTime.nowUTC)) //