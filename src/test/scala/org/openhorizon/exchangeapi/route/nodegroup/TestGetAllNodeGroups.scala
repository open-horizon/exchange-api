package org.openhorizon.exchangeapi.route.nodegroup

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection, HttpCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetAllNodeGroups extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups"

  private implicit val formats: DefaultFormats.type = DefaultFormats

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
        orgId              = "testGetAllNodeGroupsRoute1",
        orgType            = "",
        tags               = None),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testGetNodeGroups",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetAllNodeGroupsRoute2",
        orgType            = "",
        tags               = None
      ),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 3",
        label              = "testGetNodeGroups",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetAllNodeGroupsRoute3",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "TestGetAllNodeGroupsRouteHubAdmin@ibm.com",
                hashedPw    = Password.hash(HUBADMINPASSWORD),
                hubAdmin    = true,
                lastUpdated = ApiTime.nowUTC,
                orgid       = "root",
                updatedBy   = "root/root",
                username    = "root/TestGetAllNodeGroupsRouteHubAdmin"),
        UserRow(admin       = true,
                email       = "orgAdmin@ibm.com",
                hashedPw    = Password.hash(ORGADMINPASSWORD),
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "root/root",
                username    = TESTORGS.head.orgId + "/orgAdmin"),
        UserRow(admin       = false,
                email       = "orgUser@ibm.com",
                hashedPw    = Password.hash(USERPASSWORD),
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "root/root",
                username    = TESTORGS.head.orgId + "/orgUser"),
        UserRow(admin       = false,
                email       = "orgUser@ibm.com",
                hashedPw    = "",
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = TESTORGS(1).orgId,
                updatedBy   = "root/root",
                username    = TESTORGS(1).orgId + "/orgUser"),
        UserRow(admin       = false,
                email       = "orgUser@ibm.com",
                hashedPw    = Password.hash(USERPASSWORD),
                hubAdmin    = false,
                lastUpdated = ApiTime.nowUTC,
                orgid       = TESTORGS(2).orgId,
                updatedBy   = "root/root",
                username    = TESTORGS(2).orgId + "/orgUser"))

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
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node1",
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
                token              = Password.hash(NODETOKEN),
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node2",
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
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(1).orgId + "/node3",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node4",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(2).username, //org user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch = "",
                id = TESTORGS.head.orgId + "/node5",
                heartbeatIntervals = "",
                lastHeartbeat = Option(ApiTime.nowUTC),
                lastUpdated = ApiTime.nowUTC,
                msgEndPoint = "",
                name = "",
                nodeType = "",
                orgid = TESTORGS.head.orgId,
                owner = TESTUSERS(1).username, //org admin
                pattern = "",
                publicKey = "",
                regServices = "",
                softwareVersions = "",
                token = "",
                userInput = ""))

  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(admin = false,
                     description = Option("empty node group"),
                     group = 0, //gets automatically set by DB
                     lastUpdated = ApiTime.nowUTC,
                     name = "TestGetAllNodeGroupsRoute_empty",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin = false,
                     description = Option("test node group"),
                     group = 0, //gets automatically set by DB
                     lastUpdated = ApiTime.nowUTC,
                     name = "TestGetAllNodeGroupsRoute_main",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin = false,
                     description = Option("other node group"),
                     group = 0, //gets automatically set by DB
                     lastUpdated = ApiTime.nowUTC,
                     name = "TestGetAllNodeGroupsRoute_other",
                     organization = TESTORGS(1).orgId),
        NodeGroupRow(admin = true,
                     description = Option("admin node group"),
                     group = 0, //gets automatically set by DB
                     lastUpdated = ApiTime.nowUTC,
                     name = "TestGetAllNodeGroupsRoute_admin",
                     organization = TESTORGS.head.orgId))

  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())

  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORGADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USERPASSWORD))
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES(0).id + ":" + NODETOKEN))
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS(0).id + ":" + AGBOTTOKEN))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      (OrgsTQ ++= TESTORGS) andThen
      (UsersTQ ++= TESTUSERS) andThen
      (AgbotsTQ ++= TESTAGBOTS) andThen
      (NodesTQ ++= TESTNODES) andThen
      (NodeGroupTQ ++= TESTNODEGROUPS)
    ), AWAITDURATION)
    val mainGroup: Long = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS(1).name).result), AWAITDURATION).head.group
    val nodeGroupAdmin: Long = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS.last.name).result), AWAITDURATION).head.group
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(NodeGroupAssignmentRow(group = mainGroup,
                                 node = TESTNODES.head.id),
          NodeGroupAssignmentRow(group = mainGroup,
                                 node = TESTNODES(1).id),
          NodeGroupAssignmentRow(group = mainGroup,
                                 node = TESTNODES(2).id),
          NodeGroupAssignmentRow(group = nodeGroupAdmin,
                                 node = TESTNODES(3).id),
          NodeGroupAssignmentRow(group = nodeGroupAdmin,
                                 node = TESTNODES.last.id))
    
    Await.ready(DBCONNECTION.run(NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testGetAllNodeGroupsRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetAllNodeGroupsRoute").delete andThen
      UsersTQ.filter(_.username startsWith TESTUSERS(0).username).delete
    ), AWAITDURATION)
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

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + " -- as root -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 3)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.head.name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.last.name))
    
    val emptyGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.head.name).head
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    val nodeGroupAdmin: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.last.name).head
    
    assert(emptyGroup.admin === TESTNODEGROUPS.head.admin)
    assert(emptyGroup.description === TESTNODEGROUPS.head.description.get)
    assert(emptyGroup.lastUpdated === TESTNODEGROUPS.head.lastUpdated)
    assert(emptyGroup.members.isEmpty)
    assert(emptyGroup.name === TESTNODEGROUPS.head.name)
    
    assert(mainGroup.admin === TESTNODEGROUPS(1).admin)
    assert(mainGroup.description === TESTNODEGROUPS(1).description.get)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES.head.id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
  
    assert(nodeGroupAdmin.admin === TESTNODEGROUPS.last.admin)
    assert(nodeGroupAdmin.description === TESTNODEGROUPS.last.description.get)
    assert(nodeGroupAdmin.lastUpdated === TESTNODEGROUPS.last.lastUpdated)
    assert(nodeGroupAdmin.members.length === 2)
    assert(nodeGroupAdmin.members.contains(TESTNODES(3).id.split("/")(1)))
    assert(nodeGroupAdmin.members.contains(TESTNODES.last.id.split("/")(1)))
    assert(nodeGroupAdmin.name === TESTNODEGROUPS.last.name)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + " -- as org admin -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  
    assert(response.code === HttpCode.OK.intValue)
  
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
  
    assert(responseBody.nodeGroups.length === 3)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.head.name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.last.name))
  
    val emptyGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.head.name).head
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    val nodeGroupAdmin: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.last.name).head
  
    assert(emptyGroup.admin === TESTNODEGROUPS.head.admin)
    assert(emptyGroup.description === TESTNODEGROUPS.head.description.get)
    assert(emptyGroup.lastUpdated === TESTNODEGROUPS.head.lastUpdated)
    assert(emptyGroup.members.isEmpty)
    assert(emptyGroup.name === TESTNODEGROUPS.head.name)
  
    assert(mainGroup.admin === TESTNODEGROUPS(1).admin)
    assert(mainGroup.description === TESTNODEGROUPS(1).description.get)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES.head.id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
  
    assert(nodeGroupAdmin.admin === TESTNODEGROUPS.last.admin)
    assert(nodeGroupAdmin.description === TESTNODEGROUPS.last.description.get)
    assert(nodeGroupAdmin.lastUpdated === TESTNODEGROUPS.last.lastUpdated)
    assert(nodeGroupAdmin.members.length === 2)
    assert(nodeGroupAdmin.members.contains(TESTNODES(3).id.split("/")(1)))
    assert(nodeGroupAdmin.members.contains(TESTNODES.last.id.split("/")(1)))
    assert(nodeGroupAdmin.name === TESTNODEGROUPS.last.name)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + " -- as user -- 200 OK, only owned nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 3)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.head.name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.last.name))
    
    val emptyGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.head.name).head
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    val nodeGroupAdmin: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.last.name).head
    
    
    assert(emptyGroup.admin === TESTNODEGROUPS.head.admin)
    assert(emptyGroup.description === TESTNODEGROUPS.head.description.get)
    assert(emptyGroup.lastUpdated === TESTNODEGROUPS.head.lastUpdated)
    assert(emptyGroup.members.isEmpty)
    assert(emptyGroup.name === TESTNODEGROUPS.head.name)
    
    assert(mainGroup.admin === TESTNODEGROUPS(1).admin)
    assert(mainGroup.description === TESTNODEGROUPS(1).description.get)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 1)
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
  
    assert(nodeGroupAdmin.admin === TESTNODEGROUPS.last.admin)
    assert(nodeGroupAdmin.description !== TESTNODEGROUPS.last.description.get)
    assert(nodeGroupAdmin.description === "")
    assert(nodeGroupAdmin.lastUpdated === TESTNODEGROUPS.last.lastUpdated)
    assert(nodeGroupAdmin.members.length === 1)
    assert(nodeGroupAdmin.members.contains(TESTNODES(3).id.split("/")(1)))
    assert(nodeGroupAdmin.name === TESTNODEGROUPS.last.name)
  }
  
  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + " -- as hub admin -- 403 access denied") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(0).orgId + ROUTE).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + " -- as agbot -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  
    assert(response.code === HttpCode.OK.intValue)
  
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
  
    assert(responseBody.nodeGroups.length === 3)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.head.name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS.last.name))
  
    val emptyGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.head.name).head
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    val nodeGroupAdmin: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS.last.name).head
  
    assert(emptyGroup.admin === TESTNODEGROUPS.head.admin)
    assert(emptyGroup.description === TESTNODEGROUPS.head.description.get)
    assert(emptyGroup.lastUpdated === TESTNODEGROUPS.head.lastUpdated)
    assert(emptyGroup.members.isEmpty)
    assert(emptyGroup.name === TESTNODEGROUPS.head.name)
  
    assert(mainGroup.admin === TESTNODEGROUPS(1).admin)
    assert(mainGroup.description === TESTNODEGROUPS(1).description.get)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES.head.id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
  
    assert(nodeGroupAdmin.admin === TESTNODEGROUPS.last.admin)
    assert(nodeGroupAdmin.description !== TESTNODEGROUPS.last.description.get)
    assert(nodeGroupAdmin.description === "")
    assert(nodeGroupAdmin.lastUpdated === TESTNODEGROUPS.last.lastUpdated)
    assert(nodeGroupAdmin.members.length === 2)
    assert(nodeGroupAdmin.members.contains(TESTNODES(3).id.split("/")(1)))
    assert(nodeGroupAdmin.members.contains(TESTNODES.last.id.split("/")(1)))
    assert(nodeGroupAdmin.name === TESTNODEGROUPS.last.name)
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
