package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, PutNodeGroupsRequest, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, PostPutNodeGroupsRequest, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostNodeGroupRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept", "application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups/"


  private implicit val formats: DefaultFormats.type = DefaultFormats

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals = "",
        description = "Test Organization 1",
        label = "TestPostNodeGroupRoute",
        lastUpdated = ApiTime.nowUTC,
        limits = "",
        orgId = "TestPostNodeGroupRoute",
        orgType = "",
        tags = None))

  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin = true,
      email = "",
      hashedPw = "$2a$10$LNH5rZACF8YnbHWtUFnULOxNecpZoq6qXG0iI47OBCdNtugUehRLG", // TestPutAgentConfigMgmt/admin1:admin1pw
      hubAdmin = false,
      lastUpdated = ApiTime.nowUTC,
      orgid = "TestPostNodeGroupRoute",
      updatedBy = "",
      username = "TestPostNodeGroupRoute/admin1"),
      UserRow(admin = false,
        email = "",
        hashedPw = "$2a$10$DGVQ73YXt2IXtxA3bMmxSu0q5wEj26UgE.6hGryB5BedV1E945yki", // TestPutAgentConfigMgmt/u1:a1pw
        hubAdmin = false,
        lastUpdated = ApiTime.nowUTC,
        orgid = "TestPostNodeGroupRoute",
        updatedBy = "",
        username = "TestPostNodeGroupRoute/u1"),
      UserRow(admin = false,
        email = "",
        hashedPw = "$2a$10$DGVQ73YXt2IXtxA3bMmxSu0q5wEj26UgE.6hGryB5BedV1E945yki", // TestPutAgentConfigMgmt/u1:a1pw
        hubAdmin = false,
        lastUpdated = ApiTime.nowUTC,
        orgid = "TestPostNodeGroupRoute",
        updatedBy = "",
        username = "TestPostNodeGroupRoute/u2"))

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id = "TestPostNodeGroupRoute/agbot",
        orgid = "TestPostNodeGroupRoute",
        token = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i",
        name = "",
        owner = "TestPostNodeGroupRoute/u1", //org 1 user
        msgEndPoint = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey = ""
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch = "",
        id = TESTORGS.head.orgId + "/node0",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
        owner = TESTUSERS.head.username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "$2a$10$fEe00jBiITDA7RnRUGFH.upsISQ3cm93pdvkbJaFr5ZC/5kxhyZ4i",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS.head.orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
        owner = TESTUSERS.head.username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS.head.orgId + "/node2",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
        owner = TESTUSERS.head.username, //org admin
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      ),
      NodeRow(
        arch = "",
        id = TESTORGS.head.orgId + "/node3",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
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
        id = TESTORGS.head.orgId + "/node4",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
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
        id = TESTORGS.head.orgId + "/node5",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
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
        id = TESTORGS.head.orgId + "/node6",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
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
        id = TESTORGS.head.orgId + "/node7",
        heartbeatIntervals = "",
        lastHeartbeat = Some(ApiTime.nowUTC),
        lastUpdated = ApiTime.nowUTC,
        msgEndPoint = "",
        name = "",
        nodeType = "",
        orgid = TESTORGS.head.orgId,
        owner = TESTUSERS(2).username, //org user 2
        pattern = "",
        publicKey = "",
        regServices = "",
        softwareVersions = "",
        token = "",
        userInput = ""
      )
    )


  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())


  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES)
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeGroupRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "TestPostNodeGroupRoute").delete), AWAITDURATION)

    DBCONNECTION.getDb.close()
  }

  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "king  -- 201 Ok - Create as Root") {

    val members: Seq[String] =
      Seq("node0", "node1", "node2")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(
        description = "This is a node group for root user",
        members = members)

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroupRoute/hagroups/king").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString

    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === HttpCode.POST_OK.intValue)
    val dbNodeGroup: NodeGroupRow = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === "king").result), AWAITDURATION).head
    assert(dbNodeGroup.description === requestBody.description)
    assert(dbNodeGroup.name === "king")
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroupRoute").filter(_.resource === ResChangeResource.NODEGROUP.toString).filter(_.operation === ResChangeOperation.CREATED.toString).result),AWAITDURATION).nonEmpty)
    val nodeRCs: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroupRoute").filter(_.resource === ResChangeCategory.NODE.toString).result), AWAITDURATION)
    assert(nodeRCs.exists(_.id === "node0"))
    assert(nodeRCs.exists(_.id === "node1"))
    assert(nodeRCs.exists(_.id === "node2"))
  }

  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "queen  -- 201 Ok - Create as User: u1") {

    val members: Seq[String] =
      Seq("node3", "node4")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(
        description = "This is a node group for user:u1",
        members = members)

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroupRoute/hagroups/queen").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPostNodeGroupRoute/u1:u1pw"))).asString
    info(members.length.toString)

    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === HttpCode.POST_OK.intValue)
    val dbNodeGroup: NodeGroupRow = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === "queen").result), AWAITDURATION).head
    assert(dbNodeGroup.description === requestBody.description)
    assert(dbNodeGroup.name === "queen")
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroupRoute").filter(_.resource === ResChangeResource.NODEGROUP.toString).filter(_.operation === ResChangeOperation.CREATED.toString).result),AWAITDURATION).nonEmpty)
    val nodeRCs: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroupRoute").filter(_.resource === ResChangeCategory.NODE.toString).result), AWAITDURATION)
    assert(nodeRCs.exists(_.id === "node3"))
    assert(nodeRCs.exists(_.id === "node4"))
  }

  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "test  -- 403 Access Denied - User: u1") {

    val members: Seq[String] =
      Seq("node5", "node6", "node7")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(
        description = "This should fail because user:u1 is trying to create group with nodes it doesn't own",
        members = members)

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroupRoute/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization","Basic " + ApiUtils.encode("TestPostNodeGroupRoute/u1:u1pw"))).asString


    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === HttpCode.ACCESS_DENIED.intValue)

  }

  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "test  -- 409 Already Exists - Root") {

    val members: Seq[String] =
      Seq("node0", "node1")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(
        description = "This should fail because root is trying to create group with nodes already in a group",
        members = members)

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroupRoute/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === HttpCode.ALREADY_EXISTS2.intValue)

  }

  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "king  -- 409 Already Exists - Duplicate Groups") {

    val members: Seq[String] =
      Seq("node6", "node7")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(
        description = "This should fail because root is trying to create group that already exists",
        members = members)

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroupRoute/hagroups/king").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString


    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === HttpCode.ALREADY_EXISTS2.intValue)

  }
}