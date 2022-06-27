package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.{ApiTime, ApiUtils, GetNodeGroupsResponse, HttpCode, Password, Role, TestDBConnection}
import com.horizon.exchangeapi.tables.{AgbotRow, AgbotsTQ, NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResourceChangesTQ, UserRow, UsersTQ}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetNodeGroupsRoute extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups"

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
        label              = "testGetNodeGroups",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupsRoute1",
        orgType            = "",
        tags               = None),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testGetNodeGroups",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupsRoute2",
        orgType            = "",
        tags               = None
      ),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 3",
        label              = "testGetNodeGroups",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupsRoute3",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(
      UserRow(
        username    = "root/TestGetNodeGroupsRouteHubAdmin",
        orgid       = "root",
        hashedPw    = Password.hash(HUBADMINPASSWORD),
        admin       = false,
        hubAdmin    = true,
        email       = "TestGetNodeGroupsRouteHubAdmin@ibm.com",
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
      ),
      UserRow(
        username    = TESTORGS(2).orgId + "/orgUser",
        orgid       = TESTORGS(2).orgId,
        hashedPw    = Password.hash(USERPASSWORD),
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
        id                 = TESTORGS(0).orgId + "/node1",
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
        token              = Password.hash(NODETOKEN),
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
        id                 = TESTORGS(1).orgId + "/node3",
        heartbeatIntervals = "",
        lastHeartbeat      = Some(ApiTime.nowUTC),
        lastUpdated        = ApiTime.nowUTC,
        msgEndPoint        = "",
        name               = "",
        nodeType           = "",
        orgid              = TESTORGS(1).orgId,
        owner              = TESTUSERS(3).username, //org user
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
        group = 0, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestGetNodeGroupsRoute_empty"
      ),
      NodeGroupRow(
        description = "test node group",
        group = 0, //gets automatically set by DB
        organization = TESTORGS(0).orgId,
        updated = ApiTime.nowUTC,
        name = "TestGetNodeGroupsRoute_main"
      ),
      NodeGroupRow(
        description = "other node group",
        group = 0, //gets automatically set by DB
        organization = TESTORGS(1).orgId,
        updated = ApiTime.nowUTC,
        name = "TestGetNodeGroupsRoute_other"
      )
    )

  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())

  private val ROOTAUTH = ("Authorization","Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORGADMINAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val USERAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USERPASSWORD))
  private val NODEAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))
  private val AGBOTAUTH = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (NodeGroupTQ ++= TESTNODEGROUPS)
    ), AWAITDURATION)
    val mainGroup = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === "TestGetNodeGroupsRoute_main").result), AWAITDURATION).head.group
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(
        NodeGroupAssignmentRow(
          group = mainGroup,
          node = TESTNODES(0).id
        ),
        NodeGroupAssignmentRow(
          group = mainGroup,
          node = TESTNODES(1).id
        ),
        NodeGroupAssignmentRow(
          group = mainGroup,
          node = TESTNODES(2).id
        )
      )
    Await.ready(DBCONNECTION.getDb.run(
      NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testGetNodeGroupsRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetNodeGroupsRoute").delete andThen
      UsersTQ.filter(_.username startsWith TESTUSERS(0).username).delete
    ), AWAITDURATION)
    DBCONNECTION.getDb.close()
  }

  test("GET /orgs/doesNotExist" + ROUTE + " -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.isEmpty)
  }

  test("GET /orgs/" + TESTORGS(2).orgId + ROUTE + " -- no node groups in org -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(2).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.isEmpty)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as root -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.length === 2)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(0).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    val emptyGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(0).name).head
    val mainGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    assert(emptyGroup.name === TESTNODEGROUPS(0).name)
    assert(emptyGroup.updated === TESTNODEGROUPS(0).updated)
    assert(emptyGroup.members.isEmpty)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.updated === TESTNODEGROUPS(1).updated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as org admin -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.length === 2)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(0).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    val emptyGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(0).name).head
    val mainGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    assert(emptyGroup.name === TESTNODEGROUPS(0).name)
    assert(emptyGroup.updated === TESTNODEGROUPS(0).updated)
    assert(emptyGroup.members.isEmpty)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.updated === TESTNODEGROUPS(1).updated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as user -- 200 OK, only owned nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.length === 2)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(0).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    val emptyGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(0).name).head
    val mainGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    assert(emptyGroup.name === TESTNODEGROUPS(0).name)
    assert(emptyGroup.updated === TESTNODEGROUPS(0).updated)
    assert(emptyGroup.members.isEmpty)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.updated === TESTNODEGROUPS(1).updated)
    assert(mainGroup.members.length === 1)
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as hub admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as agbot -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.OK.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.length === 2)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(0).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    val emptyGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(0).name).head
    val mainGroup = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    assert(emptyGroup.name === TESTNODEGROUPS(0).name)
    assert(emptyGroup.updated === TESTNODEGROUPS(0).updated)
    assert(emptyGroup.members.isEmpty)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.updated === TESTNODEGROUPS(1).updated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS(0).orgId + ROUTE + " -- as node -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + " -- as org admin in other org -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

}
