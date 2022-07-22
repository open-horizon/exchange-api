package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.tables.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ, UserRow, UsersTQ}
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


  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(description   = "",
      group              = 0L,
      organization       = "TestDeleteNodeGroup",
      updated            = ApiTime.nowUTC,
      name               = "king"),
      NodeGroupRow(description   = "",
        group              = 0L,
        organization       = "TestDeleteNodeGroup",
        updated            = ApiTime.nowUTC,
        name               = "queen"))

  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin = true,
      email = "",
      hashedPw = "$2a$10$LNH5rZACF8YnbHWtUFnULOxNecpZoq6qXG0iI47OBCdNtugUehRLG", // TestPutAgentConfigMgmt/admin1:admin1pw
      hubAdmin = false,
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestDeleteNodeGroup",
      updatedBy = "",
      username = "TestDeleteNodeGroup/admin1"),
      UserRow(admin = false,
        email = "",
        hashedPw = "$2a$10$DGVQ73YXt2IXtxA3bMmxSu0q5wEj26UgE.6hGryB5BedV1E945yki", // TestPutAgentConfigMgmt/u1:a1pw
        hubAdmin = false,
        lastUpdated = ApiTime.nowUTC,
        orgid = "TestDeleteNodeGroup",
        updatedBy = "",
        username = "TestDeleteNodeGroup/u1"),
      UserRow(admin = false,
        email = "",
        hashedPw = "$2a$10$DGVQ73YXt2IXtxA3bMmxSu0q5wEj26UgE.6hGryB5BedV1E945yki", // TestPutAgentConfigMgmt/u2:a1pw
        hubAdmin = false,
        lastUpdated = ApiTime.nowUTC,
        orgid = "TestDeleteNodeGroup",
        updatedBy = "",
        username = "TestDeleteNodeGroup/u2"))

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals = "",
        description = "Test Organization 1",
        label = "TestDeleteNodeGroup",
        lastUpdated = ApiTime.nowUTC,
        limits = "",
        orgId = "TestDeleteNodeGroup",
        orgType = "",
        tags = None))

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node0",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(0).username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(0).username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node2",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(0).username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node3",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(1).username, //org user 1
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node4",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(1).username, //org user 1
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node5",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(2).username, //org user 2
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node6",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(2).username, //org user 2
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS(0).orgId + "/node7",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS(0).orgId,
        owner = TESTUSERS(2).username, //org user 2
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      )
    )

  var kingGroup: Long = -1

  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
    val groupId: Long = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(1).name).result), AWAITDURATION).head.group
    kingGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(0).name).result), AWAITDURATION).head.group
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(
        NodeGroupAssignmentRow(
          group = kingGroup, //"king"
          node = TESTNODES(0).id //node0
        ),
        NodeGroupAssignmentRow(
          group = groupId, //"queen"
          node = TESTNODES(5).id //node5 , owned by u2
        ),
        NodeGroupAssignmentRow(
          group = groupId,
          node = TESTNODES(6).id  //node6
        ),
        NodeGroupAssignmentRow(
          group = groupId,
          node = TESTNODES(7).id  //node7
        )
      )
    Await.ready(DBCONNECTION.getDb.run(
      NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS
    ), AWAITDURATION)
  }

  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestDeleteNodeGroup").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestDeleteNodeGroup").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/randomgroup -- 404 Not Found - Group Name") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroups/randomgroup").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("GET /orgs/somerandomorg/hagroups/king -- 404 Not Found - Organization") {
    val response: HttpResponse[String] = Http(URL + "somerandomorg/hagroups/king").method("get").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/king -- 204 Deleted - As root") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroups/king").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.DELETED.intValue)

    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.getNodeGroupName("TestDeleteNodeGroup", "queen").result), AWAITDURATION).size === 1)
    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.getNodeGroupName("TestDeleteNodeGroup","king").result), AWAITDURATION).size === 0)
    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === kingGroup).result), AWAITDURATION).size === 0)
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "TestDeleteNodeGroup")
      .filter(_.id === "king")
      .filter(_.category === ResChangeCategory.NODEGROUP.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODEGROUP.toString)
      .filter(_.operation === ResChangeOperation.DELETED.toString)
      .result), AWAITDURATION).nonEmpty)
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "TestDeleteNodeGroup")
      .filter(_.id === TESTNODES(0).id)
      .filter(_.category === ResChangeCategory.NODE.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODE.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION).nonEmpty)
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/queen -- 403 Access Denied - As u1 trying to delete nodes it doesn't own") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroups/queen").method("delete").headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestDeleteNodeGroup/u1:u1pw"))).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === HttpCode.ACCESS_DENIED.intValue)

  }
}