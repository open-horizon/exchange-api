package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, PutNodeGroupsRequest, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ, UserRow, UsersTQ}
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastHeartbeat      = Option(ApiTime.nowUTC),
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
        lastUpdated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_empty"
      ),
      NodeGroupRow(
        description = "main node group",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        lastUpdated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_main"
      ),
      NodeGroupRow(
        description = "mixed node group (owner)",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        lastUpdated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_mixed_owner"
      ),
      NodeGroupRow(
        description = "mixed node group (org)",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        lastUpdated = ApiTime.nowUTC,
        name = "TestPutNodeGroupRoute_mixed_org"
      ),
      NodeGroupRow(
        description = "org 2 node group",
        group = 0L, //gets automatically set by DB
        organization = TESTORGS(1).orgId,
        lastUpdated = ApiTime.nowUTC,
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
      NodeGroupTQ.filter(_.group === emptyGroup).update(NodeGroupRow(TESTNODEGROUPS(0).description, emptyGroup, TESTNODEGROUPS(0).organization, TESTNODEGROUPS(0).lastUpdated, TESTNODEGROUPS(0).name)) andThen
      NodeGroupTQ.filter(_.group === mainGroup).update(NodeGroupRow(TESTNODEGROUPS(1).description, mainGroup, TESTNODEGROUPS(1).organization, TESTNODEGROUPS(1).lastUpdated, TESTNODEGROUPS(1).name)) andThen
      NodeGroupTQ.filter(_.group === mixedGroupOwner).update(NodeGroupRow(TESTNODEGROUPS(2).description, mixedGroupOwner, TESTNODEGROUPS(2).organization, TESTNODEGROUPS(2).lastUpdated, TESTNODEGROUPS(2).name)) andThen
      NodeGroupTQ.filter(_.group === mixedGroupOrg).update(NodeGroupRow(TESTNODEGROUPS(3).description, mixedGroupOrg, TESTNODEGROUPS(3).organization, TESTNODEGROUPS(3).lastUpdated, TESTNODEGROUPS(3).name)) andThen
      NodeGroupTQ.filter(_.group === org2Group).update(NodeGroupRow(TESTNODEGROUPS(4).description, org2Group, TESTNODEGROUPS(4).organization, TESTNODEGROUPS(4).lastUpdated, TESTNODEGROUPS(4).name)) andThen
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

  def assertNodeGroupUpdated(group: Long, nodeGroup: NodeGroupRow, reqBody: PutNodeGroupsRequest): Unit = {
    val dbNodeGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.group === group).result), AWAITDURATION).head
    if (reqBody.description.isDefined)
      assert(dbNodeGroup.description === reqBody.description.get)
    else
      assert(dbNodeGroup.description === nodeGroup.description)
    assert(dbNodeGroup.lastUpdated > nodeGroup.lastUpdated)
  }

  def assertNodeGroupNotUpdated(group: Long, nodeGroup: NodeGroupRow): Unit = {
    val dbNodeGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.group === group).result), AWAITDURATION).head
    assert(dbNodeGroup.description === nodeGroup.description)
    assert(dbNodeGroup.lastUpdated === nodeGroup.lastUpdated)
  }

  def assertResourceChangeExists(orgId: String, name: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === orgId)
      .filter(_.id === name)
      .filter(_.category === ResChangeCategory.NODEGROUP.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODEGROUP.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION).nonEmpty)
  }

  def assertNoResourceChangeExists(orgId: String, name: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === orgId).result), AWAITDURATION).isEmpty)
  }

  def assertNodeRCExists(orgId: String, id: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === orgId)
      .filter(_.id === id)
      .filter(_.category === ResChangeCategory.NODE.toString)
      .filter(_.public === "false")
      .filter(_.resource === ResChangeResource.NODE.toString)
      .filter(_.operation === ResChangeOperation.MODIFIED.toString)
      .result), AWAITDURATION).nonEmpty)
  }

  def assertNoNodeRCExists(orgId: String, id: String): Unit = {
    assert(Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ
      .filter(_.orgId === orgId)
      .filter(_.resource === ResChangeCategory.NODE.toString)
      .filter(_.id === id)
      .result), AWAITDURATION).isEmpty)
  }

  private val normalRequestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
    members = Option(Seq(TESTNODES(0).id.split("/")(1), TESTNODES(6).id.split("/")(1))),
    description = Option("new description")
  )

  test("PUT /orgs/doesNotExist" + ROUTE + TESTNODEGROUPS(1).name + " -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE  + "doesNotExist -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + "doesNotExist").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- empty request body -- 400 BAD INPUT") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- invalid request body -- 400 BAD INPUT") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- invalid format of members -- 400 BAD INPUT") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put("{\"members\":\"this should not be a string\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- just update description -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("new description!"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsNotChanged(mainGroup)
    assertNodeGroupUpdated(mainGroup, TESTNODEGROUPS(1), requestBody)
    assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(1).name)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- just update members -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = None,
      members = Option(Seq(TESTNODES(0).id.split("/")(1), TESTNODES(6).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsChanged(mainGroup, requestBody, TESTORGS(0).orgId)
    assertNodeGroupUpdated(mainGroup, TESTNODEGROUPS(1), requestBody)
    assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(1).name)
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(6).id.split("/")(1))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as user -- 201 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsChanged(mainGroup, normalRequestBody, TESTORGS(0).orgId)
    assertNodeGroupUpdated(mainGroup, TESTNODEGROUPS(1), normalRequestBody)
    assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(1).name)
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(6).id.split("/")(1))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- put members in empty group -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(5).id.split("/")(1), TESTNODES(6).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORGADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsChanged(emptyGroup, requestBody, TESTORGS(0).orgId)
    assertNodeGroupUpdated(emptyGroup, TESTNODEGROUPS(0), requestBody)
    assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(0).name)
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(5).id.split("/")(1))
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(6).id.split("/")(1))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name + " -- user tries to update group they don't own -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(3).name + " -- admin tries to update group they don't own -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(3).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORGADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name + " -- as admin -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(5).id.split("/")(1),
                           TESTNODES(6).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORGADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
  
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.organization === TESTORGS(0).orgId).filter(_.name === TESTNODEGROUPS(2).name).result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    assert(nodeGroup.head.description === requestBody.description.get)
  
    val assignments: Seq[String] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.join(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(2).name)).on(_.group === _.group).sortBy(_._1.node.asc.nullsFirst).map(_._1.node).result), AWAITDURATION)
    assert(assignments.length === requestBody.members.get.length)
    assert(assignments.contains(TESTNODES(5).id))
    assert(assignments.contains(TESTNODES(6).id))
  
    val nodeGroupChange: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId)/*.filter(_.id === TESTNODEGROUPS(2).name)*/.filter(_.category === ResChangeCategory.NODEGROUP.toString).result), AWAITDURATION)
    assert(nodeGroupChange.length === 1)
    assert(nodeGroupChange.head.id === TESTNODEGROUPS(2).name)
    assert(nodeGroupChange.head.operation === ResChangeOperation.MODIFIED.toString)
    assert(nodeGroupChange.head.resource === ResChangeResource.NODEGROUP.toString)
    assert(nodeGroupChange.head.public === "false")
  
    val nodeChanges: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS(0).orgId).filter(_.category === ResChangeCategory.NODE.toString).sortBy(_.id.asc.nullsFirst).result), AWAITDURATION)
    assert(nodeChanges.length === 4)
    
    val nodesThatHaveChanged: Seq[String] = nodeChanges.map(_.id)
    assert(nodesThatHaveChanged.contains(TESTNODES(1).id.split("/")(1)))
    assert(nodesThatHaveChanged.contains(TESTNODES(2).id.split("/")(1)))
    assert(nodesThatHaveChanged.contains(TESTNODES(5).id.split("/")(1)))
    assert(nodesThatHaveChanged.contains(TESTNODES(6).id.split("/")(1)))
  
    assert(nodeChanges.head.id === TESTNODES(1).id.split("/")(1))
    assert(nodeChanges.head.category === ResChangeCategory.NODE.toString)
    assert(nodeChanges.head.operation === ResChangeOperation.MODIFIED.toString)
    assert(nodeChanges.head.public === "false")
    assert(nodeChanges.head.resource === ResChangeResource.NODE.toString)
  
    assert(nodeChanges(1).id === TESTNODES(2).id.split("/")(1))
    assert(nodeChanges(1).category === ResChangeCategory.NODE.toString)
    assert(nodeChanges(1).operation === ResChangeOperation.MODIFIED.toString)
    assert(nodeChanges(1).public === "false")
    assert(nodeChanges(1).resource === ResChangeResource.NODE.toString)
  
    assert(nodeChanges(2).id === TESTNODES(5).id.split("/")(1))
    assert(nodeChanges(2).category === ResChangeCategory.NODE.toString)
    assert(nodeChanges(2).operation === ResChangeOperation.MODIFIED.toString)
    assert(nodeChanges(2).public === "false")
    assert(nodeChanges(2).resource === ResChangeResource.NODE.toString)
  
    assert(nodeChanges.last.id === TESTNODES(6).id.split("/")(1))
    assert(nodeChanges.last.category === ResChangeCategory.NODE.toString)
    assert(nodeChanges.last.operation === ResChangeOperation.MODIFIED.toString)
    assert(nodeChanges.last.public === "false")
    assert(nodeChanges.last.resource === ResChangeResource.NODE.toString)
    
    
    // assertAssignmentsChanged(mixedGroupOwner, requestBody, TESTORGS(0).orgId)
    // assertNodeGroupUpdated(mixedGroupOwner, TESTNODEGROUPS(2), requestBody)
    // assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(2).name)
    // assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(1).id)
    // assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(2).id)
    // assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(5).id)
    // ssertNodeRCExists(TESTORGS(0).orgId, TESTNODES(6).id)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- user tries to insert node they don't own -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(5).id.split("/")(1), TESTNODES(6).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- admin tries to insert node they don't own -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(5).id.split("/")(1), TESTNODES(6).id.split("/")(1), TESTNODES(7).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORGADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- try to put node from one group into a different group -- 409 CONFLICT") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(0).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  }

  //the behavior of this route might need to be changed to make this case return 400 BAD INPUT instead
  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- try to insert node that doesn't exist -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq("doesNotExist"))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(1).orgId + ROUTE + TESTNODEGROUPS(4).name + " -- admin tries to update group in other org -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + TESTNODEGROUPS(4).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ORGADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name + " -- as root -- 201 OK") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = Option(Seq(TESTNODES(5).id.split("/")(1), TESTNODES(6).id.split("/")(1)))
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(2).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.POST_OK.intValue)
    assertAssignmentsChanged(mixedGroupOwner, requestBody, TESTORGS(0).orgId)
    assertNodeGroupUpdated(mixedGroupOwner, TESTNODEGROUPS(2), requestBody)
    assertResourceChangeExists(TESTORGS(0).orgId, TESTNODEGROUPS(2).name)
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(1).id.split("/")(1))
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(2).id.split("/")(1))
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(5).id.split("/")(1))
    assertNodeRCExists(TESTORGS(0).orgId, TESTNODES(6).id.split("/")(1))
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- as hub admin -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- as node -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("PUT /orgs/" + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name + " -- as agbot -- 403 ACCESS DENIED") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(
      description = Option("NEW DESCRIPTION"),
      members = None
    )
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

}
