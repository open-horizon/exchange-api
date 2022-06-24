package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.tables.{ManagementPoliciesTQ, NodeGroupAssignmentTQ, NodeGroupAssignments, NodeGroupRow, NodeGroupTQ, NodeMgmtPolStatusRow, NodeMgmtPolStatuses, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}


class TestDeleteNodeGroupRoute extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.


  private val TESTNODEGROUP: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(description   = "",
      group              = "1",
      organization       = "TestDeleteNodeGroup",
      updated            = ApiTime.nowUTC,
      name               = "king1"),
      NodeGroupRow(description   = "",
        group              = "1",
        organization       = "TestDeleteNodeGroup",
        updated            = ApiTime.nowUTC,
        name               = "king2"))
  private val TESTNODE: NodeRow =
    NodeRow(arch               = "",
      id                 = "TestDeleteNodeGroup/n1",
      heartbeatIntervals = "",
      lastHeartbeat      = Option(ApiTime.nowUTC),
      lastUpdated        = ApiTime.nowUTC,
      msgEndPoint        = "",
      name               = "",
      nodeType           = "",
      orgid              = "TestDeleteNodeGroup",
      owner              = "TestDeleteNodeGroup/u1",
      pattern            = "TestDeleteNodeGroup/p1",
      publicKey          = "",
      regServices        = "",
      softwareVersions   = "",
      token              = "",
      userInput          = "")
  private val TESTORGANIZATION: OrgRow =
    OrgRow(heartbeatIntervals = "",
      description        = "",
      label              = "",
      lastUpdated        = ApiTime.nowUTC,
      orgId              = "TestDeleteNodeGroup",
      orgType            = "",
      tags               = None,
      limits             = "")
  private val TESTUSER: UserRow =
    UserRow(admin       = false,
      hubAdmin    = false,
      email       = "",
      hashedPw    = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i", // TestNodeDeleteMgmtPolStatus/u1:u1pw
      lastUpdated = ApiTime.nowUTC,
      orgid       = "TestDeleteNodeGroup",
      updatedBy   = "",
      username    = "TestDeleteNodeGroup/u1")


  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ += TESTORGANIZATION) andThen
      (UsersTQ += TESTUSER) andThen
      (NodesTQ += TESTNODE) andThen
      (NodeGroupTQ ++= TESTNODEGROUP)), AWAITDURATION)
  }

  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestDeleteNodeGroup").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestDeleteNodeGroup").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }

  // Node Group Assignments that are dynamically needed, specific to the test case.
  def fixtureNodeGroupAssignment(testCode: Seq[(String, String)] => Any, testData: Seq[(String, String)]): Any = {
    try {
      Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.delete), AWAITDURATION)
  }

  // Users that are dynamically needed, specific to the test case.
  def fixtureUsers(testCode: Seq[UserRow] => Any, testData: Seq[UserRow]): Any = {
    try{
      Await.result(DBCONNECTION.getDb.run(UsersTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(UsersTQ.filter(_.username inSet testData.map(_.username)).delete), AWAITDURATION)
  }


  test("DELETE /orgs/TestDeleteNodeGroup/hagroup/randomgroup -- 404 Not Found - Group Name") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroup/randomgroup").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/somerandomorg/hagroup/king1 -- 404 Not Found - Organization") {
    val response: HttpResponse[String] = Http(URL + "somerandomorg/hagroup/king1").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroup/king2 -- 204 Deleted - root") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroup/king2").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.DELETED.intValue)

    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.getNodeGroupName("king1").result), AWAITDURATION).size === 1)
    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.getNodeGroupName("king2").result), AWAITDURATION).size === 0)
  }
}