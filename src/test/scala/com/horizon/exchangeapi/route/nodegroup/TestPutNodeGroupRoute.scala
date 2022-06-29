package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, PutNodeGroupsRequest, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPutNodeGroupRoute extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups/"

  private implicit val formats = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val USERPASSWORD = "userpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"

  private val TESTORGS: Seq[OrgRow] =
    Seq(
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 1",
        label              = "testPutNodeGroup",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPutNodeGroupRoute1",
        orgType            = "",
        tags               = None),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testPutNodeGroup",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testPutNodeGroupRoute2",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestPutNodeGroupRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestPutNodeGroupRouteHubAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgAdmin",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(ORGADMINPASSWORD),
        admin       = true,
        hubAdmin    = false,
        email       = "orgAdmin@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(0).orgId + "/orgUser",
        orgid       = TESTORGS(0).orgId,
        hashedPw    = Password.hash(USERPASSWORD),
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      ),
      UserRow(
        username    = TESTORGS(1).orgId + "/orgUser",
        orgid       = TESTORGS(1).orgId,
        hashedPw    = "",
        admin       = false,
        hubAdmin    = false,
        email       = "orgUser@ibm.com",
        lastUpdated = ApiTime.nowUTC,
        updatedBy   = "root/root"
      )
    )

  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id            = TESTORGS(0).orgId + "/agbot",
        orgid         = TESTORGS(0).orgId,
        token         = Password.hash(AGBOTTOKEN),
        name          = "",
        owner         = TESTUSERS(2).username, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node0",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = Password.hash(NODETOKEN),
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node1",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node2",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node3",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(1).orgId + "/node4",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(3).username, //org 2 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node5",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(1).username, //org admin
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(0).orgId + "/node6",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(0).orgId,
        owner              = TESTUSERS(2).username, //org user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      ),
      NodeRow(
        arch               = "",
        id                 = TESTORGS(1).orgId + "/node7",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(3).username, //org 2 user
        pattern            = "",
        publicKey          = "",
        regServices        = "",
        softwareVersions   = "",
        token              = "",
        userInput          = ""
      )
    )

  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(
      NodeGroupRow(
        description = "empty node group",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_empty"
      ),
      NodeGroupRow(
        description = "main node group",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_main"
      ),
      NodeGroupRow(
        description = "mixed node group (owner)",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_mixed_owner"
      ),
      NodeGroupRow(
        description = "mixed node group (org)",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_mixed_org"
      ),
      NodeGroupRow(
        description = "org 2 node group",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(1).orgId,
        updated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_other_org"
      )
    )

  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())
  var TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] = null

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USERPASSWORD))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))

  var emptyGroup: Long = -1
  var mainGroup: Long = -1
  var mixedGroupOwner: Long = -1
  var mixedGroupOrg: Long = -1
  var org2Group: Long = -1

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
        (UsersTQ ++= TESTUSERS) andThen
        (AgbotsTQ ++= TESTAGBOTS) andThen
        (NodesTQ ++= TESTNODES) andThen
        (NodeGroupTQ ++= TESTNODEGROUPS)
    ), AWAITDURATION)
    emptyGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(0).name).result), AWAITDURATION).head.group
    mainGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(1).name).result), AWAITDURATION).head.group
    mixedGroupOwner = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(2).name).result), AWAITDURATION).head.group
    mixedGroupOrg = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(3).name).result), AWAITDURATION).head.group
    org2Group = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(4).name).result), AWAITDURATION).head.group
    TESTNODEGROUPASSIGNMENTS =
      Seq(
        NodeGroupAssignmentRow(
          group = mainGroup,
          node = TESTNODES(0).id
        ),
        NodeGroupAssignmentRow(
          group = mixedGroupOwner,
          node = TESTNODES(1).id
        ),
        NodeGroupAssignmentRow(
          group = mixedGroupOwner,
          node = TESTNODES(2).id
        ),
        NodeGroupAssignmentRow(
          group = mixedGroupOrg,
          node = TESTNODES(3).id
        ),
        NodeGroupAssignmentRow(
          group = mixedGroupOrg,
          node = TESTNODES(4).id
        )
      )
    Await.ready(DBCONNECTION.getDb.run(
      NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testPutNodeGroupRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testPutNodeGroupRoute").delete andThen
      UsersTQ.filter(_.username startsWith TESTUSERS(0).username).delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      NodeGroupAssignmentTQ.filter(a => a.group === emptyGroup || a.group === mainGroup || a.group === mixedGroupOwner || a.group === mixedGroupOrg || a.group === org2Group).delete andThen
      (NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS) andThen
      TESTNODEGROUPS(0).update andThen
      TESTNODEGROUPS(1).update andThen
      TESTNODEGROUPS(2).update andThen
      TESTNODEGROUPS(3).update andThen
      TESTNODEGROUPS(4).update andThen
      ResourceChangesTQ.filter(a => a.orgId === TESTORGS(0).orgId || a.orgId === TESTORGS(1).orgId).delete
    ), AWAITDURATION)
  }

  def assertAssignmentsChanged(group: Long, reqBody: PutNodeGroupsRequest, orgId: String): Unit = {
    val assignments: Seq[String] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === group).result), AWAITDURATION).map(_.node)
    assert(assignments.length === reqBody.members.get.length)
    for (member <- reqBody.members.get) {
      assert(assignments.contains(orgId + "/" + member))
    }
  }

  def assertAssignmentsNotChanged(group: Long): Unit = {
    val oldAssignments: Seq[String] = TESTNODEGROUPASSIGNMENTS.filter(_.group === group).map(_.node)
    val assignments: Seq[String] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === group).result), AWAITDURATION).map(_.node)
    assert(assignments.length === oldAssignments.length)
    for (member <- oldAssignments) {
      assert(assignments.contains(member))
    }
  }

  def assertNodeGroupUpdated(group: Long, nodeGroup: NodeGroupRow): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.group === group).result), AWAITDURATION).head.updated > nodeGroup.updated)
  }

  def assertNodeGroupNotUpdated(group: Long, nodeGroup: NodeGroupRow): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.group === group).result), AWAITDURATION).head.updated === nodeGroup.updated)
  }

  /*def assertResourceChangeAdded(orgId: String, )*/

  test("PUT /orgs/doesNotExist" + ROUTE + TESTNODEGROUPS(1).name + " -- 404 NOT FOUND") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      members = Some(Seq(TESTNODES(0).id.split("/")(1), TESTNODES(6).id.split("/")(1))),
      description = None
    )
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    assertAssignmentsNotChanged(mainGroup)
    assertNodeGroupNotUpdated(mainGroup, TESTNODEGROUPS(1))
    val resourceChange = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === "doesNotExist")
      .filter(_.id === TESTNODEGROUPS(1).name)
      .filter(_.category === ResChangeCategory.NODEGROUP.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODEGROUP.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION)
    assert(resourceChange.isEmpty)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as user -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      members = Some(Seq(TESTNODES(0).id.split("/")(1), TESTNODES(6).id.split("/")(1))),
      description = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsChanged(mainGroup, requestBody, TESTORGS(0).orgId)
    assertNodeGroupUpdated(mainGroup, TESTNODEGROUPS(1))
    val resourceChange = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === TESTORGS(0).orgId)
      .filter(_.id === TESTNODEGROUPS(1).name)
      .filter(_.category === ResChangeCategory.NODEGROUP.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODEGROUP.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION)
    assert(resourceChange.nonEmpty)
  }

}
