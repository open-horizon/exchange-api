package org.openhorizon.exchangeapi.route.nodegroup

import org.apache.pekko.http.scaladsl.model.StatusCodes
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
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestGetNodeGroup extends AnyFunSuite with BeforeAndAfterAll {

  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups/"

  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
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
        label              = "testGetNodeGroup",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupRoute1",
        orgType            = "",
        tags               = None),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 2",
        label              = "testGetNodeGroup",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupRoute2",
        orgType            = "",
        tags               = None
      ),
      OrgRow(
        heartbeatIntervals = "",
        description        = "Test Organization 3",
        label              = "testGetNodeGroup",
        lastUpdated        = ApiTime.nowUTC,
        limits             = "",
        orgId              = "testGetNodeGroupRoute3",
        orgType            = "",
        tags               = None
      )
    )

  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = true,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = "root",
                password     = Option(Password.hash(HUBADMINPASSWORD)),
                username     = "TestGetNodeGroupRouteHubAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = Option(Password.hash(ORGADMINPASSWORD)),
                username     = "orgAdmin"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "root"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(1).orgId,
                password     = None,
                username     = "orgUser"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS(2).orgId,
                password     = Option(Password.hash(USERPASSWORD)),
                username     = "orgUser"))
  }
  
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(
      AgbotRow(
        id            = TESTORGS.head.orgId + "/agbot",
        orgid         = TESTORGS.head.orgId,
        token         = Password.hash(AGBOTTOKEN),
        name          = "",
        owner         = TESTUSERS(2).user, //org 1 user
        msgEndPoint   = "",
        lastHeartbeat = ApiTime.nowUTC,
        publicKey     = ""
      )
    )

  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node1",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(1).user, //org admin
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash(NODETOKEN),
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node2",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = ApiTime.nowUTC,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(2).user, //org user
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
                owner              = TESTUSERS(3).user, //org user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
      NodeRow(arch                 = "",
              id                   = TESTORGS.head.orgId + "/node4",
              heartbeatIntervals   = "",
              lastHeartbeat        = Option(ApiTime.nowUTC),
              lastUpdated          = ApiTime.nowUTC,
              msgEndPoint          = "",
              name                 = "",
              nodeType             = "",
              orgid                = TESTORGS.head.orgId,
              owner                = TESTUSERS(2).user, //org1 user
              pattern              = "",
              publicKey            = "",
              regServices          = "",
              softwareVersions     = "",
              token                = "",
              userInput            = ""))

  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(admin        = false,
                     description  = Option("empty node group"),
                     group        = 0, //gets automatically set by DB
                     lastUpdated  = ApiTime.nowUTC,
                     name         = "TestGetNodeGroupRoute_empty",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin        = false,
                     description  = Option("test node group"),
                     group        = 0, //gets automatically set by DB
                     lastUpdated  = ApiTime.nowUTC,
                     name         = "TestGetNodeGroupRoute_main",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin        = false,
                     description  = Option("other node group"),
                     group        = 0, //gets automatically set by DB
                     lastUpdated  = ApiTime.nowUTC,
                     name         = "TestGetNodeGroupRoute_other",
                     organization = TESTORGS(1).orgId),
        NodeGroupRow(admin        = true,
                     description  = Option("admin node group"),
                     group        = 0, //gets automatically set by DB
                     lastUpdated  = ApiTime.nowUTC,
                     name         = "TestGetNodeGroupRoute_admin",
                     organization = TESTORGS.head.orgId))

  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())

  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val HUBADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(0).organization + "/" + TESTUSERS(0).username + ":" + HUBADMINPASSWORD))
  private val ORGADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).organization + "/" + TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).organization + "/" + TESTUSERS(2).username + ":" + USERPASSWORD))
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
                                 node = TESTNODES(0).id),
          NodeGroupAssignmentRow(group = mainGroup,
                                 node = TESTNODES(1).id),
          NodeGroupAssignmentRow(group = mainGroup,
                                 node = TESTNODES(2).id),
          NodeGroupAssignmentRow(group = nodeGroupAdmin,
                                 node = TESTNODES.last.id))
    Await.ready(DBCONNECTION.run(
      NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS
    ), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(
      ResourceChangesTQ.filter(_.orgId startsWith "testGetNodeGroupRoute").delete andThen
      OrgsTQ.filter(_.orgid startsWith "testGetNodeGroupRoute").delete andThen
      UsersTQ.filter(_.organization === "root")
             .filter(_.username startsWith TESTUSERS(0).username).delete
    ), AWAITDURATION)
  }

  test("GET /orgs/doesNotExist" + ROUTE + TESTNODEGROUPS(1).name + " -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.isEmpty)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + "doesNotExist -- 404 NOT FOUND") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + "doesNotExist").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.NotFound.intValue)
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    assert(responseBody.nodeGroups.isEmpty)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as root -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
  
    assert(mainGroup.admin === false)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(0).name + " -- as root -- 200 OK") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(0).name).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(0).name))
    
    val emptyGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(0).name).head
  
    assert(emptyGroup.admin === false)
    assert(emptyGroup.name === TESTNODEGROUPS(0).name)
    assert(emptyGroup.lastUpdated === TESTNODEGROUPS(0).lastUpdated)
    assert(emptyGroup.members.isEmpty)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as org admin -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
  
    assert(mainGroup.admin === false)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as user -- 200 OK, only owned nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
    
    assert(mainGroup.admin === false)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 1)
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }
  
  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS.last.name + " - 200 OK - get my nodes from an admin node group - user") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS.last.name).headers(ACCEPT).headers(USERAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.head
    
    assert(mainGroup.admin === true)
    assert(mainGroup.description === "")
    assert(mainGroup.lastUpdated === TESTNODEGROUPS.last.lastUpdated)
    assert(mainGroup.members.length === 1)
    assert(mainGroup.members.head === (TESTNODES.last.id.split("/")(1)))
    assert(mainGroup.name === TESTNODEGROUPS.last.name)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as hub admin -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(HUBADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as agbot -- 200 OK, all nodes included") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(AGBOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.OK.intValue)
    
    val responseBody: GetNodeGroupsResponse = JsonMethods.parse(response.body).extract[GetNodeGroupsResponse]
    
    assert(responseBody.nodeGroups.length === 1)
    assert(responseBody.nodeGroups.exists(_.name === TESTNODEGROUPS(1).name))
    
    val mainGroup: NodeGroupResp = responseBody.nodeGroups.filter(_.name === TESTNODEGROUPS(1).name).head
  
    assert(mainGroup.admin === false)
    assert(mainGroup.name === TESTNODEGROUPS(1).name)
    assert(mainGroup.lastUpdated === TESTNODEGROUPS(1).lastUpdated)
    assert(mainGroup.members.length === 2)
    assert(mainGroup.members.contains(TESTNODES(0).id.split("/")(1)))
    assert(mainGroup.members.contains(TESTNODES(1).id.split("/")(1)))
  }

  test("GET /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as node -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(NODEAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

  test("GET /orgs/" + TESTORGS(1).orgId + ROUTE + TESTNODEGROUPS(1).name + " -- as org admin in other org -- 403 ACCESS DENIED") {
    val response: HttpResponse[String] = Http(URL + TESTORGS(1).orgId + ROUTE + TESTNODEGROUPS(1).name).headers(ACCEPT).headers(ORGADMINAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    assert(response.code === StatusCodes.Forbidden.intValue)
  }

}
