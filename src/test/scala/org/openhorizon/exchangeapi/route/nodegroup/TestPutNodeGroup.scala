package org.openhorizon.exchangeapi.route.nodegroup

import org.openhorizon.exchangeapi.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, Password, Role, TestDBConnection}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.Role
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotRow, AgbotsTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.math.Ordered.orderingToOrdered

class TestPutNodeGroup extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Accept","application/json")
  private val CONTENT: (String, String) = ("Content-Type", "application/json")
  private val AWAITDURATION: Duration = 15.seconds
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val ROUTE = "/hagroups/"

  private implicit val formats: DefaultFormats.type = DefaultFormats

  private val HUBADMINPASSWORD = "hubadminpassword"
  private val ORGADMINPASSWORD = "orgadminpassword"
  private val USERPASSWORD = "userpassword"
  private val NODETOKEN = "nodetoken"
  private val AGBOTTOKEN = "agbottoken"
  
  private val INITIALTIMESTAMP: Timestamp = ApiTime.nowUTCTimestamp
  private val INITIALTIMESTAMPSTRING: String = fixFormatting(INITIALTIMESTAMP.toInstant
                                                                             .atZone(ZoneId.of("UTC"))
                                                                             .withZoneSameInstant(ZoneId.of("UTC"))
                                                                             .toString)
  
  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(heartbeatIntervals = "",
               description        = "Test Organization 1",
               label              = "testPutNodeGroup",
               lastUpdated        = INITIALTIMESTAMPSTRING,
               limits             = "",
               orgId              = "TestPutNodeGroupRoute1",
               orgType            = "",
               tags               = None),
        OrgRow(heartbeatIntervals = "",
               description        = "Test Organization 2",
               label              = "testPutNodeGroup",
               lastUpdated        = INITIALTIMESTAMPSTRING,
               limits             = "",
               orgId              = "TestPutNodeGroupRoute2",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "TestPutNodeGroupRouteHubAdmin@ibm.com",
                hashedPw    = Password.hash(HUBADMINPASSWORD),
                hubAdmin    = true,
                lastUpdated = INITIALTIMESTAMPSTRING,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "root/root",
                username    = TESTORGS.head.orgId + "/hubAdmin"),
        UserRow(admin       = true,
                email       = "orgAdmin@ibm.com",
                hashedPw    = Password.hash(ORGADMINPASSWORD),
                hubAdmin    = false,
                lastUpdated = INITIALTIMESTAMPSTRING,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "root/root",
                username    = TESTORGS.head.orgId + "/orgAdmin"),
        UserRow(admin       = false,
                email       = "orgUser@ibm.com",
                hashedPw    = Password.hash(USERPASSWORD),
                hubAdmin    = false,
                lastUpdated = INITIALTIMESTAMPSTRING,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "root/root",
                username    = TESTORGS.head.orgId + "/orgUser"),
        UserRow(admin       = false,
                email       = "orgUser@ibm.com",
                hashedPw    = "",
                hubAdmin    = false,
                lastUpdated = INITIALTIMESTAMPSTRING,
                orgid       = TESTORGS(1).orgId,
                updatedBy   = "root/root",
                username    = TESTORGS(1).orgId + "/orgUser"))
  private val TESTAGBOTS: Seq[AgbotRow] =
    Seq(AgbotRow(id            = TESTORGS.head.orgId + "/agbot",
                 lastHeartbeat = INITIALTIMESTAMPSTRING,
                 msgEndPoint   = "",
                 name          = "",
                 orgid         = TESTORGS.head.orgId,
                 owner         = TESTUSERS(2).username, //org 1 user
                 publicKey     = "",
                 token         = Password.hash(AGBOTTOKEN)))
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
                owner              = TESTUSERS(2).username, //org user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = Password.hash(NODETOKEN),
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
                owner              = TESTUSERS(2).username, //org user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(0).orgId + "/node2",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
                msgEndPoint        = "",
                name               = "",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS(1).username, //org admin
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
                owner              = TESTUSERS(1).username, //org admin
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(1).orgId + "/node4",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
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
                owner              = TESTUSERS(1).username, //org admin
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
                owner              = TESTUSERS(2).username, //org user
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS(1).orgId + "/node7",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(INITIALTIMESTAMPSTRING),
                lastUpdated        = INITIALTIMESTAMPSTRING,
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
                userInput          = ""))
  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(description = Option("empty node group"),
                     group = 0L, //gets automatically set by DB
                     lastUpdated = INITIALTIMESTAMPSTRING,
                     name = "TestPutNodeGroupRoute_empty",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(description = Option("main node group"),
                     group = 0L, //gets automatically set by DB
                     lastUpdated = INITIALTIMESTAMPSTRING,
                     name = "TestPutNodeGroupRoute_main",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(description = Option("mixed node group (owner)"),
                     group = 0L, //gets automatically set by DB
                     lastUpdated = INITIALTIMESTAMPSTRING,
                     name = "TestPutNodeGroupRoute_mixed_owner",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(description = Option("mixed node group (org)"),
                     group = 0L, //gets automatically set by DB
                     lastUpdated = INITIALTIMESTAMPSTRING,
                     name = "TestPutNodeGroupRoute_mixed_org",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(description = Option("org 2 node group"),
                     group = 0L, //gets automatically set by DB
                     lastUpdated = INITIALTIMESTAMPSTRING,
                     name = "TestPutNodeGroupRoute_other_org",
                     organization = TESTORGS(1).orgId))
  
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val HUBADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS.head.username + ":" + HUBADMINPASSWORD))
  private val ORGADMINAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(1).username + ":" + ORGADMINPASSWORD))
  private val USERAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTUSERS(2).username + ":" + USERPASSWORD))
  private val NODEAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTNODES.head.id + ":" + NODETOKEN))
  private val AGBOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(TESTAGBOTS.head.id + ":" + AGBOTTOKEN))
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (AgbotsTQ ++= TESTAGBOTS) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPutNodeGroupRoute").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPutNodeGroupRoute").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPutNodeGroup").delete), AWAITDURATION)
  }
  
  private val normalRequestBody: PutNodeGroupsRequest =
    PutNodeGroupsRequest(description = Option("new description"),
                         members = Option(Seq(TESTNODES.head.id.split("/")(1),
                                              TESTNODES(6).id.split("/")(1))))
  
  // Node Groups that are dynamically needed, specific to the test case.
  def fixtureNodeGroups(testCode: Seq[NodeGroupRow] => Any, testData: Seq[NodeGroupRow]): Any = {
    var nodeGroups: Seq[NodeGroupRow] = Seq()
    try {
      nodeGroups = Await.result(DBCONNECTION.getDb.run((NodeGroupTQ returning NodeGroupTQ) ++= testData), AWAITDURATION)
      testCode(nodeGroups)
    }
    finally
      Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.group inSet nodeGroups.map(_.group)).delete), AWAITDURATION)
  }
  
  test("PUT /orgs/doesNotExist" + ROUTE + TESTNODEGROUPS(1).name + " -- 404 not found - bad organization - root") {
    val response: HttpResponse[String] = Http(URL + "doesNotExist" + ROUTE + TESTNODEGROUPS(1).name).put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE  + "doesNotExist -- 404 not found - bad node group - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + "doesNotExist").put(Serialization.write(normalRequestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS.head.name + " -- 403 access denied - hub administrator") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(description = Option("NEW DESCRIPTION"), members = None)
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(HUBADMINAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS.head.name + " -- 403 access denied - node") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(description = Option("NEW DESCRIPTION"), members = None)
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(NODEAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS.head.name + " -- 403 access denied - agbot") {
    val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(description = Option("NEW DESCRIPTION"), members = None)
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(0).name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(AGBOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- 400 bad input - invalid request body - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).put("{\"invalidKey\":\"invalidValue\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- 400 bad input - invalid type of members - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).put("{\"members\":\"this should not be a string\"}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name + " -- 400 bad input - invalid type of description - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUPS(1).name).put("{\"description\":[0]}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
    info("code: " + response.code)
    info("body: " + response.body)
    
    assert(response.code === HttpCode.BAD_INPUT.intValue)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng0" + " -- 403 access denied - update a node group without ownership - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = Option("description should remain the same"),
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng0",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.getDb.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES(2).id)), AWAITDURATION)
        
        val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(description = Option("some new description"), members = None)
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
  
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng1" + " -- 403 access denied - remove node from a node group without ownership - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng1",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.getDb.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES(2).id)), AWAITDURATION)
        
        val requestBody: PutNodeGroupsRequest = PutNodeGroupsRequest(description = None,
                                                                     members = Option(Seq()))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng2" + " -- 403 access denied - add node to a node group without ownership - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng2",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        val requestBody: PutNodeGroupsRequest =
          PutNodeGroupsRequest(description = None,
                               members = Option(Seq(TESTNODES(2).id.split("/")(1))))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng3" + " -- 403 access denied - add node that does not exist - root") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng3",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        val requestBody: PutNodeGroupsRequest =
          PutNodeGroupsRequest(description = None,
                               members = Option(Seq("somerandomnode")))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
      }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng9" + " -- 403 access denied - add node to an admin node group - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(admin = true,
                       description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng9",
                       organization = TESTORGS.head.orgId))
                  
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.getDb.run(
          NodeGroupAssignmentTQ ++= Seq(NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                               node = TESTNODES.head.id),
                                        NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                               node = TESTNODES(2).id))), AWAITDURATION)
        
        val requestBody: PutNodeGroupsRequest =
          PutNodeGroupsRequest(description = None,
                               members = Option(Seq(TESTNODES(1).id.split("/")(1))))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(USERAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.ACCESS_DENIED.intValue)
    }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng5" + " -- 409 conflict - add node that is assigned to another node group - root") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng4",
                       organization = TESTORGS.head.orgId),
          NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng5",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.getDb.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES.head.id)), AWAITDURATION)
        
        val requestBody: PutNodeGroupsRequest =
          PutNodeGroupsRequest(description = None,
                               members = Option(Seq(TESTNODES.head.id.split("/")(1))))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.last.name).put(Serialization.write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
      }, TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng6" + " -- 201 ok - empty request body - root") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = Option("description should remain the same"),
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng6",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.getDb.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES.head.id)), AWAITDURATION)
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put("{}").headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.PUT_OK.intValue)
        
        val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId).filter(_.name === TESTNODEGROUP.head.name).result), AWAITDURATION)
        assert(nodeGroup.sizeIs == 1)
        
        //assert(nodeGroup.head.admin === true)
        assert(nodeGroup.head.description === TESTNODEGROUP.head.description)
        assert(nodeGroup.head.group === assignedTestNodeGroups.head.group)
        assert(nodeGroup.head.lastUpdated !== TESTNODEGROUP.head.lastUpdated)
        assert(nodeGroup.head.name === TESTNODEGROUP.head.name)
        assert(nodeGroup.head.organization === TESTNODEGROUP.head.organization)
        
        val assignedNodes: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === assignedTestNodeGroups.head.group).result), AWAITDURATION)
        assert(assignedNodes.sizeIs == 1)
        
        assert(assignedNodes.head.group === assignedTestNodeGroups.head.group)
        assert(assignedNodes.head.node === TESTNODES.head.id)
        
        val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS.head.orgId).sortBy(change => (change.category.asc.nullsLast, change.id.asc.nullsLast)).result), AWAITDURATION)
        assert(changes.sizeIs == 1)
        
        assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
        assert(changes.head.id === TESTNODEGROUP.head.name)
        assert(INITIALTIMESTAMP < changes.head.lastUpdated)
        assert(changes.head.operation === ResChangeOperation.MODIFIED.toString)
        assert(changes.head.orgId === TESTORGS.head.orgId)
        assert(changes.head.public === "false")
        assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
    }, testData = TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng7" + " -- 201 ok - add and remove node assignments, and change description - root") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = Option("description should change"),
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng7",
                       organization = TESTORGS.head.orgId))
    
    val requestBody: PutNodeGroupsRequest =
      PutNodeGroupsRequest(description = Option("some new description"),
                           members = Option(Seq(TESTNODES(1).id.split("/")(1),
                                                TESTNODES(2).id.split("/")(1))))
    
    fixtureNodeGroups(assignedTestNodeGroups => {
      Await.ready(DBCONNECTION.getDb.run(
        NodeGroupAssignmentTQ ++= Seq(NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,node = TESTNODES.head.id), NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group, node = TESTNODES(1).id))), AWAITDURATION)
      
      val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
      info("code: " + response.code)
      info("body: " + response.body)
      
      assert(response.code === HttpCode.PUT_OK.intValue)
      
      val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId).filter(_.name === TESTNODEGROUP.head.name).result), AWAITDURATION)
      assert(nodeGroup.sizeIs == 1)
      
      //assert(nodeGroup.head.admin === true)
      assert(nodeGroup.head.description !== TESTNODEGROUP.head.description)
      assert(nodeGroup.head.group === assignedTestNodeGroups.head.group)
      assert(nodeGroup.head.lastUpdated !== TESTNODEGROUP.head.lastUpdated)
      assert(nodeGroup.head.name === TESTNODEGROUP.head.name)
      assert(nodeGroup.head.organization === TESTNODEGROUP.head.organization)
      
      val assignedNodes: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === assignedTestNodeGroups.head.group).sortBy(_.node.asc.nullsLast).result), AWAITDURATION)
      assert(assignedNodes.sizeIs == 2)
      
      assert(assignedNodes.head.group === assignedTestNodeGroups.head.group)
      assert(assignedNodes.head.node === TESTNODES(1).id)
      
      assert(assignedNodes(1).group === assignedTestNodeGroups.head.group)
      assert(assignedNodes(1).node === TESTNODES(2).id)
      
      val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS.head.orgId).sortBy(change => (change.category.asc.nullsLast, change.id.asc.nullsLast)).result), AWAITDURATION)
      changes.map(change => (info("change: " + change.changeId + "  " + change.orgId + "  " + change.category + "  " +  change.resource + "  " + change.operation + "  " + change.id + "  " + change.lastUpdated)))
      
      assert(changes.sizeIs == 4)
      
      assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
      assert(changes.head.id === TESTNODEGROUP.head.name)
      assert(INITIALTIMESTAMP < changes.head.lastUpdated)
      assert(changes.head.operation === ResChangeOperation.MODIFIED.toString)
      assert(changes.head.orgId === TESTORGS.head.orgId)
      assert(changes.head.public === "false")
      assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
      
      assert(changes(1).category === ResChangeCategory.NODE.toString)
      assert(changes(1).id === TESTNODES.head.id.split("/")(1))
      assert(INITIALTIMESTAMP < changes(1).lastUpdated)
      assert(changes(1).operation === ResChangeOperation.MODIFIED.toString)
      assert(changes(1).orgId === TESTORGS.head.orgId)
      assert(changes(1).public === "false")
      assert(changes(1).resource === ResChangeResource.NODE.toString)
      
      assert(changes(2).category === ResChangeCategory.NODE.toString)
      assert(changes(2).id === TESTNODES(1).id.split("/")(1))
      assert(INITIALTIMESTAMP < changes(2).lastUpdated)
      assert(changes(2).operation === ResChangeOperation.MODIFIED.toString)
      assert(changes(2).orgId === TESTORGS.head.orgId)
      assert(changes(2).public === "false")
      assert(changes(2).resource === ResChangeResource.NODE.toString)
      
      assert(changes.last.category === ResChangeCategory.NODE.toString)
      assert(changes.last.id === TESTNODES(2).id.split("/")(1))
      assert(INITIALTIMESTAMP < changes.last.lastUpdated)
      assert(changes.last.operation === ResChangeOperation.MODIFIED.toString)
      assert(changes.last.orgId === TESTORGS.head.orgId)
      assert(changes.last.public === "false")
      assert(changes.last.resource === ResChangeResource.NODE.toString)
      
      assert(changes.head.lastUpdated === changes(1).lastUpdated && changes.head.lastUpdated === changes(2).lastUpdated && changes.head.lastUpdated === changes.last.lastUpdated)
    }, testData = TESTNODEGROUP)
  }
  
  test("PUT /orgs/" + TESTORGS.head.orgId + ROUTE + "TestPutNodeGroup_ng8" + " -- 201 ok - add node to node group without any assignments - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestPutNodeGroup_ng8",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        val requestBody: PutNodeGroupsRequest =
          PutNodeGroupsRequest(description = None,
                               members = Option(Seq(TESTNODES.head.id.split("/")(1))))
        
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + ROUTE + TESTNODEGROUP.head.name).put(write(requestBody)).headers(ACCEPT).headers(CONTENT).headers(ROOTAUTH).asString
        info("code: " + response.code)
        info("body: " + response.body)
        
        assert(response.code === HttpCode.PUT_OK.intValue)
        
        val nodeGroup: Seq[NodeGroupRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId).filter(_.name === TESTNODEGROUP.head.name).result), AWAITDURATION)
        assert(nodeGroup.sizeIs == 1)
        
        //assert(nodeGroup.head.admin === false)
        assert(nodeGroup.head.description === TESTNODEGROUP.head.description)
        assert(nodeGroup.head.group === assignedTestNodeGroups.head.group)
        assert(nodeGroup.head.lastUpdated !== TESTNODEGROUP.head.lastUpdated)
        assert(nodeGroup.head.name === TESTNODEGROUP.head.name)
        assert(nodeGroup.head.organization === TESTNODEGROUP.head.organization)
        
        val assignedNodes: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.filter(_.group === assignedTestNodeGroups.head.group).result), AWAITDURATION)
        assert(assignedNodes.sizeIs == 1)
        
        assert(assignedNodes.head.group === assignedTestNodeGroups.head.group)
        assert(assignedNodes.head.node === TESTNODES.head.id)
        
        val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId === TESTORGS.head.orgId).sortBy(change => (change.category.asc.nullsLast, change.id.asc.nullsLast)).result), AWAITDURATION)
        assert(changes.sizeIs == 2)
        
        assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
        assert(changes.head.id === TESTNODEGROUP.head.name)
        assert(INITIALTIMESTAMP < changes.head.lastUpdated)
        assert(changes.head.operation === ResChangeOperation.MODIFIED.toString)
        assert(changes.head.orgId === TESTORGS.head.orgId)
        assert(changes.head.public === "false")
        assert(changes.head.resource === ResChangeResource.NODEGROUP.toString)
        
        assert(changes.last.category === ResChangeCategory.NODE.toString)
        assert(changes.last.id === TESTNODES.head.id.split("/")(1))
        assert(INITIALTIMESTAMP < changes.last.lastUpdated)
        assert(changes.last.operation === ResChangeOperation.MODIFIED.toString)
        assert(changes.last.orgId === TESTORGS.head.orgId)
        assert(changes.last.public === "false")
        assert(changes.last.resource === ResChangeResource.NODE.toString)
        
        assert(changes.head.lastUpdated === changes.last.lastUpdated)
      }, testData = TESTNODEGROUP)
  }
}
