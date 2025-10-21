package org.openhorizon.exchangeapi.route.nodegroup

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.table
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ, PostPutNodeGroupsRequest}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.{Instant, ZoneId}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.math.Ordered.orderingToOrdered

class TestPostNodeGroup extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  private val ACCEPT: (String, String) = ("Accept", "application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups/"


  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  private val INITIALTIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  private val INITIALTIMESTAMPSTRING: String = INITIALTIMESTAMP.toString

  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "Test Organization 1",
               label              = "TestPostNodeGroup",
               lastUpdated        = INITIALTIMESTAMPSTRING,
               limits             = "",
               orgId              = "TestPostNodeGroup",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestPostNodeGroup",
                password     = Option(Password.hash("admin1pw")),
                username     = "admin1"),
        UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestPostNodeGroup",
                password     = Option(Password.hash("u1pw")),
                username     = "u1"),
        UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestPostNodeGroup",
                password     = Option(Password.hash("u2pw")),
                username     = "u2"))
  }
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(id            = "TestPostNodeGroup/agbot",
                 orgid         = "TestPostNodeGroup",
                 token         = Password.hash("password"),
                 name          = "",
                 owner         = TESTUSERS(1).user, //org 1 user
                 msgEndPoint   = "",
                 lastHeartbeat = INITIALTIMESTAMPSTRING,
                 publicKey     = ""))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node0",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS.head.user, //org admin
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash("n0pw"),
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node1",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS.head.user, //org admin
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node2",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS.head.user, //org admin
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node3",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(1).user, //org user 1
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node4",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(1).user, //org user 1
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node5",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(2).user, //org user 2
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node6",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(2).user, //org user 2
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node7",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(2).user, //org user 2
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))


  //since 'group' is dynamically set when Node Groups are added to the DB, we must define NodeGroupAssignments after Node Groups are added (dynamically in beforeAll())


  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))

  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (AgbotsTQ ++= TESTAGBOTS) andThen
                                 (NodesTQ ++= TESTNODES)), AWAITDURATION)
  }

  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeGroup").delete andThen
                                 OrgsTQ.filter(_.orgid startsWith "TestPostNodeGroup").delete), AWAITDURATION)
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeGroup").delete andThen
                                 NodeGroupTQ.filter(_.organization startsWith "TestPostNodeGroup").delete), AWAITDURATION)
  }
  
  test("POST /orgs/" + "somerandomorg" + ROUTE + "test  -- 404 Not Found - bad organization - root") {
    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("Bad organization"),
                               members = Option(Seq("node0")))
    
    val request: HttpResponse[String] = Http(URL + "somerandomorg/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
  
    assert(request.code === StatusCodes.NotFound.intValue)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "test  -- 403 Access Denied - user does not own nodes - User: u1") {
    val members: Seq[String] = Seq("node5",
                                   "node6",
                                   "node7")
    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("This should fail because user:u1 is trying to create group with nodes it doesn't own"),
                               members = Option(members))
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestPostNodeGroup/u1:u1pw"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Forbidden.intValue)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "test  -- 403 Access Denied - bad node - root") {
    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("Bad node"),
                               members = Option(Seq("somerandomnode")))
    
    val request: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Forbidden.intValue)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "test  -- 409 Already Exists - assigning nodes to more than one node group - root") {
    val testDataGroup: Long =
      Await.result(DBCONNECTION.run(
        (NodeGroupTQ returning NodeGroupTQ.map(_.group)) += NodeGroupRow(description = Option(""),
                                                                         group = 0L,
                                                                         lastUpdated = INITIALTIMESTAMPSTRING,
                                                                         name = "ng0",
                                                                         organization = TESTORGS.head.orgId)), AWAITDURATION)
    Await.ready(DBCONNECTION.run(NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = testDataGroup, node = TESTNODES.head.id)), AWAITDURATION)
    
    val members: Seq[String] = Seq("node0",
                                   "node1")
    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("This should fail because root is trying to create group with nodes already in a group"),
                               members = Option(members))
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/test").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Conflict.intValue)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "king  -- 409 Already Exists - attempt creating the same node group twice - root") {
    Await.result(DBCONNECTION.run(
      NodeGroupTQ += NodeGroupRow(description = Option(""),
                                  group = 0L,
                                  lastUpdated = INITIALTIMESTAMPSTRING,
                                  name = "king",
                                  organization = TESTORGS.head.orgId)), AWAITDURATION)
    
    val members: Seq[String] = Seq("node6", "node7")
    val requestBody: PostPutNodeGroupsRequest = PostPutNodeGroupsRequest(description = Option("This should fail because root is trying to create group that already exists"), members = Option(members))
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/king").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Conflict.intValue)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "king  -- 201 Ok - default - root") {
    val members: Seq[String] = Seq("node0",
                                   "node1",
                                   "node2")

    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("This is a node group for root user"),
                               members = Option(members))

    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/king").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)

    assert(request.code === StatusCodes.Created.intValue)
    
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === "TestPostNodeGroup").result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    
    assert(nodeGroup.head.admin === true)
    assert(nodeGroup.head.description === requestBody.description)
    assert(INITIALTIMESTAMPSTRING < nodeGroup.head.lastUpdated)
    assert(nodeGroup.head.name === "king")
    assert(nodeGroup.head.organization === TESTORGS.head.orgId)
  
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === nodeGroup.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
    assert(nodeAssignments.length === members.length)
    
    assert(nodeAssignments.head.node === (TESTORGS.head.orgId + "/" + members.head))
    assert(nodeAssignments(1).node === (TESTORGS.head.orgId + "/" + members(1)))
    assert(nodeAssignments.last.node === (TESTORGS.head.orgId + "/" + members.last))
  
    val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroup").sortBy(_.category.asc.nullsLast).sortBy(_.id.asc.nullsLast).result), AWAITDURATION)
    assert(changes.length === 4)
  
    assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changes.head.id === "king")
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.head.orgId === TESTORGS.head.orgId)
    assert(changes.head.operation === ResChangeOperation.CREATED.toString)
    assert(changes.head.public === "false")
    assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
    
    assert(changes(1).category === ResChangeCategory.NODE.toString)
    assert(changes(1).id === members.head)
    assert(INITIALTIMESTAMP < (changes(1).lastUpdated))
    assert(changes(1).orgId === TESTORGS.head.orgId)
    assert(changes(1).operation === ResChangeOperation.MODIFIED.toString)
    assert(changes(1).public === "false")
    assert(changes(1).resource === ResChangeResource.NODE.toString)
  
    assert(changes(2).category === ResChangeCategory.NODE.toString)
    assert(changes(2).id === members(1))
    assert(INITIALTIMESTAMP < changes(2).lastUpdated)
    assert(changes(2).orgId === TESTORGS.head.orgId)
    assert(changes(2).operation === ResChangeOperation.MODIFIED.toString)
    assert(changes(2).public === "false")
    assert(changes(2).resource === ResChangeResource.NODE.toString)
  
    assert(changes.last.category === ResChangeCategory.NODE.toString)
    assert(changes.last.id === members.last)
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.last.orgId === TESTORGS.head.orgId)
    assert(changes.last.operation === ResChangeOperation.MODIFIED.toString)
    assert(changes.last.public === "false")
    assert(changes.last.resource === ResChangeResource.NODE.toString)
    
    assert(changes.head.lastUpdated.equals(changes(1).lastUpdated) &&
           changes.head.lastUpdated.equals(changes(2).lastUpdated) &&
           changes.head.lastUpdated.equals(changes.last.lastUpdated))
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "ng1  -- 201 Ok - empty members array - root") {
    val members: Seq[String] = Seq()
    val requestBody: PostPutNodeGroupsRequest = PostPutNodeGroupsRequest(description = Option("This is a node group for root user"), members = Option(members))
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/ng1").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Created.intValue)
    
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === "TestPostNodeGroup").result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    
    assert(nodeGroup.head.admin === true)
    assert(nodeGroup.head.description === requestBody.description)
    assert(INITIALTIMESTAMPSTRING < nodeGroup.head.lastUpdated)
    assert(nodeGroup.head.name === "ng1")
    assert(nodeGroup.head.organization === TESTORGS.head.orgId)
    
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === nodeGroup.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
    assert(nodeAssignments.length === members.length)
    
    val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroup").sortBy(_.category.asc.nullsLast).sortBy(_.id.asc.nullsLast).result), AWAITDURATION)
    assert(changes.length === 1)
    
    assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changes.head.id === "ng1")
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.head.orgId === TESTORGS.head.orgId)
    assert(changes.head.operation === ResChangeOperation.CREATED.toString)
    assert(changes.head.public === "false")
    assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "ng2  -- 201 Ok - no members - root") {
    val requestBody: PostPutNodeGroupsRequest = PostPutNodeGroupsRequest(description = Option("This is a node group for root user"), members = None)
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/ng2").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Created.intValue)
    
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === "TestPostNodeGroup").result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    
    assert(nodeGroup.head.admin === true)
    assert(nodeGroup.head.description === requestBody.description)
    assert(INITIALTIMESTAMPSTRING < nodeGroup.head.lastUpdated)
    assert(nodeGroup.head.name === "ng2")
    assert(nodeGroup.head.organization === TESTORGS.head.orgId)
    
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === nodeGroup.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
    assert(nodeAssignments.isEmpty)
    
    val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroup").sortBy(_.category.asc.nullsLast).sortBy(_.id.asc.nullsLast).result), AWAITDURATION)
    assert(changes.length === 1)
    
    assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changes.head.id === "ng2")
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.head.orgId === TESTORGS.head.orgId)
    assert(changes.head.operation === ResChangeOperation.CREATED.toString)
    assert(changes.head.public === "false")
    assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "ng3  -- 201 Ok - no description - root") {
    val requestBody: PostPutNodeGroupsRequest = PostPutNodeGroupsRequest(description = None, members = None)
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/ng3").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Created.intValue)
    
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === "TestPostNodeGroup").result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    
    assert(nodeGroup.head.admin === true)
    assert(nodeGroup.head.description === requestBody.description)
    assert(INITIALTIMESTAMPSTRING < nodeGroup.head.lastUpdated)
    assert(nodeGroup.head.name === "ng3")
    assert(nodeGroup.head.organization === TESTORGS.head.orgId)
    
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === nodeGroup.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
    assert(nodeAssignments.isEmpty)
    
    val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroup").sortBy(_.category.asc.nullsLast).sortBy(_.id.asc.nullsLast).result), AWAITDURATION)
    assert(changes.length === 1)
    
    assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changes.head.id === "ng3")
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.head.orgId === TESTORGS.head.orgId)
    assert(changes.head.operation === ResChangeOperation.CREATED.toString)
    assert(changes.head.public === "false")
    assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
  }
  
  test("POST /orgs/" + TESTORGS.head.orgId + ROUTE + "ng4  -- 201 Ok - empty members array - u1") {
    val members: Seq[String] = Seq()
    val requestBody: PostPutNodeGroupsRequest =
      PostPutNodeGroupsRequest(description = Option("This is a node group for a user"),
                               members = Option(members))
    
    val request: HttpResponse[String] = Http(URL + "TestPostNodeGroup/hagroups/ng4").postData(Serialization.write(requestBody)).headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestPostNodeGroup/u1:u1pw"))).asString
    info("code: " + request.code)
    info("body: " + request.body)
    
    assert(request.code === StatusCodes.Created.intValue)
    
    val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === "TestPostNodeGroup").result), AWAITDURATION)
    assert(nodeGroup.length === 1)
    
    assert(nodeGroup.head.admin === false)
    assert(nodeGroup.head.description === requestBody.description)
    assert(INITIALTIMESTAMPSTRING < nodeGroup.head.lastUpdated)
    assert(nodeGroup.head.name === "ng4")
    assert(nodeGroup.head.organization === TESTORGS.head.orgId)
    
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === nodeGroup.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
    assert(nodeAssignments.length === members.length)
    
    val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === "TestPostNodeGroup").sortBy(_.category.asc.nullsLast).sortBy(_.id.asc.nullsLast).result), AWAITDURATION)
    assert(changes.length === 1)
    
    assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changes.head.id === "ng4")
    assert(INITIALTIMESTAMP < changes.last.lastUpdated)
    assert(changes.head.orgId === TESTORGS.head.orgId)
    assert(changes.head.operation === ResChangeOperation.CREATED.toString)
    assert(changes.head.public === "false")
    assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
  }
}