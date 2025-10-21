package org.openhorizon.exchangeapi.route.nodegroup.node

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import scalaj.http.{Http, HttpResponse}
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostNodeToNodeGroup extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  private val INITIALTIMESTAMP: String = ApiTime.nowUTC
  
  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "TestPostNodeToNodeGroup",
               lastUpdated        = INITIALTIMESTAMP,
               limits             = "",
               orgId              = "TestPostNodeToNodeGroup",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = None,
                username     = "u0"),
        UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = Option(Password.hash("u1pw")),
                username     = "u1"))
  }
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/n0",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = INITIALTIMESTAMP,
                msgEndPoint        = "",
                name               = "n0",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS.head.user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""),
        NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/n1",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
                lastUpdated        = INITIALTIMESTAMP,
                msgEndPoint        = "",
                name               = "n1",
                nodeType           = "",
                orgid              = TESTORGS.head.orgId,
                owner              = TESTUSERS.head.user,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))
  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(admin = false,
                     description  = Option(""),
                     group        = 0L,
                     lastUpdated  = INITIALTIMESTAMP,
                     name         = "ng0",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin = false,
                     description = Option(""),
                     group = 0L,
                     lastUpdated = INITIALTIMESTAMP,
                     name = "ng1",
                     organization = TESTORGS.head.orgId),
        NodeGroupRow(admin = true,
                     description = Option(""),
                     group = 0L,
                     lastUpdated = INITIALTIMESTAMP,
                     name = "ng2",
                     organization = TESTORGS.head.orgId))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
    
    val nodeGroup: Long = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS.head.name).map(_.group).result.head), AWAITDURATION)
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(NodeGroupAssignmentRow(group = nodeGroup,
                                 node = TESTNODES.head.id))
    
    Await.ready(DBCONNECTION.run(NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS), AWAITDURATION)
  
    //Http(URL + TESTORGS.head.orgId + "/users/u1").postData(write(PostPutUsersRequest("u1pw", admin = true, Option(false), "u1@hotmail.com"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPostNodeToNodeGroup").delete), AWAITDURATION)
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").delete), AWAITDURATION)
  }
  
  // Nodes that are dynamically needed, specific to the test case.
  def fixtureNodes(testCode: Seq[NodeRow] => Any, testData: Seq[NodeRow]): Any = {
    try {
      Await.result(DBCONNECTION.run(NodesTQ ++= testData), AWAITDURATION)
      testCode(testData)
    }
    finally
      Await.result(DBCONNECTION.run(NodesTQ.filter(_.id inSet testData.map(_.id)).delete), AWAITDURATION)
  }
  
  
  test("POST /orgs/someorg/hagroup/ng0/nodes/n1 -- 404 not found - Bad Organization") {
    val response: HttpResponse[String] = Http(URL + "someorg" + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("POST /orgs/TestDeleteNodeFromNodeGroup/hagroup/somenodegroup/nodes/n1 -- 404 not found - Bad Node Group") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + "somenodegroup" + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("POST /orgs/TestDeleteNodeFromNodeGroup/hagroup/ng0/nodes/somenode -- 404 not found - Bad Node") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/somenode").method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n0 -- 409 Conflict - Add the same node to the same node group twice") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.Conflict.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng1/n0 -- 409 Conflict - Add an assigned node to a different node group") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS(1).name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.Conflict.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n1 -- 403 access denied - Assigning a node owned by another user - u1") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode(TESTUSERS.last.organization + "/" + TESTUSERS.last.username + ":u1pw"))).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.Forbidden.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng2/n2 -- 403 access denied - Assigning a node to an admin owned node group - u1") {
    val TESTNODES: Seq[NodeRow] =
      Seq(NodeRow(arch = "",
                  id = TESTORGS.head.orgId + "/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat = Option(ApiTime.nowUTC),
                  lastUpdated = INITIALTIMESTAMP,
                  msgEndPoint = "",
                  name = "n2",
                  nodeType = "",
                  orgid = TESTORGS.head.orgId,
                  owner = TESTUSERS.last.user,
                  pattern = "",
                  publicKey = "",
                  regServices = "",
                  softwareVersions = "",
                  token = "",
                  userInput = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.last.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode(TESTUSERS.last.organization + "/" + TESTUSERS.last.username + ":u1pw"))).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Forbidden.intValue)
      }, TESTNODES)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng1/n3 -- 403 access denied - Assigning a node to a node group that contains nodes owned by another user - u1") {
    val TESTNODES: Seq[NodeRow] =
      Seq(NodeRow(arch = "",
                  id = TESTORGS.head.orgId + "/n3",
                  heartbeatIntervals = "",
                  lastHeartbeat = Option(ApiTime.nowUTC),
                  lastUpdated = INITIALTIMESTAMP,
                  msgEndPoint = "",
                  name = "n3",
                  nodeType = "",
                  orgid = TESTORGS.head.orgId,
                  owner = TESTUSERS.last.user,
                  pattern = "",
                  publicKey = "",
                  regServices = "",
                  softwareVersions = "",
                  token = "",
                  userInput = ""))
    
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode(TESTUSERS.last.organization + "/" + TESTUSERS.last.username + ":u1pw"))).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.Forbidden.intValue)
      }, TESTNODES)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n1 -- 201 OK - Default - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.Created.intValue)
    
    val assignedNodes: Seq[(String, String, String)] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.join(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId)).on(_.group === _.group).sortBy(_._1.node.asc.nullsLast).map(records => {(records._2.name, records._1.node, records._2.organization)}).result), AWAITDURATION)
    assert(assignedNodes.size === 2)
    
    assert(assignedNodes.head._1 === TESTNODEGROUPS.head.name)
    assert(assignedNodes.head._2 === TESTNODES.head.id)
    assert(assignedNodes.head._3 === TESTNODEGROUPS.head.organization)
    
    assert(assignedNodes.last._1 === TESTNODEGROUPS.head.name)
    assert(assignedNodes.last._2 === TESTNODES.last.id)
    assert(assignedNodes.last._3 === TESTNODEGROUPS.head.organization)
    
    val changeRecords: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").sortBy(_.category.asc.nullsLast).result), AWAITDURATION)
    assert(changeRecords.size === 2)
    
    assert(changeRecords.head.category === ResChangeCategory.NODEGROUP.toString)
    assert(changeRecords.head.id === TESTNODEGROUPS.head.name)
    assert(changeRecords.head.operation === ResChangeOperation.MODIFIED.toString)
    assert(changeRecords.head.orgId === TESTORGS.head.orgId)
    assert(changeRecords.head.public === "false")
    assert(changeRecords.head.resource === ResChangeResource.NODEGROUP.toString)
    
    assert(changeRecords.last.category  === ResChangeCategory.NODE.toString)
    assert(changeRecords.last.id        === TESTNODES.last.name)
    assert(changeRecords.last.operation === ResChangeOperation.MODIFIED.toString)
    assert(changeRecords.last.orgId     === TESTORGS.head.orgId)
    assert(changeRecords.last.public    === "false")
    assert(changeRecords.last.resource  === ResChangeResource.NODE.toString)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng2/n2 -- 201 OK - Admin adding a node to an admin node group - root") {
    val TESTNODES: Seq[NodeRow] =
      Seq(NodeRow(arch = "",
                  id = TESTORGS.head.orgId + "/n2",
                  heartbeatIntervals = "",
                  lastHeartbeat = Option(ApiTime.nowUTC),
                  lastUpdated = INITIALTIMESTAMP,
                  msgEndPoint = "",
                  name = "n2",
                  nodeType = "",
                  orgid = TESTORGS.head.orgId,
                  owner = TESTUSERS.last.user,
                  pattern = "",
                  publicKey = "",
                  regServices = "",
                  softwareVersions = "",
                  token = "",
                  userInput = ""))
  
    fixtureNodes(
      _ => {
        val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.last.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
      
        assert(response.code === StatusCodes.Created.intValue)
      
        val assignedNodes: Seq[(String, String, String)] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.join(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId).filter(_.name === TESTNODEGROUPS.last.name)).on(_.group === _.group).sortBy(_._1.node.asc.nullsLast).map(records => {(records._2.name, records._1.node, records._2.organization)}).result), AWAITDURATION)
        assert(assignedNodes.size === 1)
      
        assert(assignedNodes.head._1 === TESTNODEGROUPS.last.name)
        assert(assignedNodes.head._2 === TESTNODES.head.id)
        assert(assignedNodes.head._3 === TESTNODEGROUPS.last.organization)
      
        val changeRecords: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").sortBy(_.category.asc.nullsLast).result), AWAITDURATION)
        assert(changeRecords.size === 2)
      
        assert(changeRecords.head.category === ResChangeCategory.NODEGROUP.toString)
        assert(changeRecords.head.id === TESTNODEGROUPS.last.name)
        assert(changeRecords.head.operation === ResChangeOperation.MODIFIED.toString)
        assert(changeRecords.head.orgId === TESTORGS.head.orgId)
        assert(changeRecords.head.public === "false")
        assert(changeRecords.head.resource === ResChangeResource.NODEGROUP.toString)
      
        assert(changeRecords.last.category === ResChangeCategory.NODE.toString)
        assert(changeRecords.last.id === TESTNODES.head.name)
        assert(changeRecords.last.operation === ResChangeOperation.MODIFIED.toString)
        assert(changeRecords.last.orgId === TESTORGS.head.orgId)
        assert(changeRecords.last.public === "false")
        assert(changeRecords.last.resource === ResChangeResource.NODE.toString)
    }, TESTNODES)
  }
}
