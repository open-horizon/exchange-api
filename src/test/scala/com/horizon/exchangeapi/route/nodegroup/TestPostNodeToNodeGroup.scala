package com.horizon.exchangeapi.route.nodegroup

import com.horizon.exchangeapi.tables.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, NodesTQ, OrgRow, OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ, UserRow, UsersTQ}
import com.horizon.exchangeapi.{ApiTime, ApiUtils, HttpCode, PostPutUsersRequest, Role, TestDBConnection}
import org.checkerframework.checker.units.qual.A
import org.json4s.DefaultFormats
import org.json4s.native.Serialization.write
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.jdbc.PostgresProfile.api.{anyToShapedValue, columnExtensionMethods, columnToOrdered, longColumnType, queryDeleteActionExtensionMethods, queryInsertActionExtensionMethods, streamableQueryActionExtensionMethods, stringColumnExtensionMethods, stringColumnType}

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}

class TestPostNodeToNodeGroup extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + sys.env.getOrElse("EXCHANGE_ROOTPW", "")))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: TestDBConnection = new TestDBConnection
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
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
  private val TESTUSERS: Seq[UserRow] =
    Seq(UserRow(admin       = false,
                email       = "",
                hashedPw    = "",
                hubAdmin    = false,
                lastUpdated = INITIALTIMESTAMP,
                orgid       = TESTORGS.head.orgId,
                updatedBy   = "",
                username    = TESTORGS.head.orgId + "/u0"),
        UserRow(admin = false,
                email = "",
                hashedPw = "$2a$10$MdbvbIDPkB0Ygas/JZTABeBAv1v0D3Vn2TjRrnMd3jlftI98WqZEa",  // TestPostNodeToNodeGroup/u1:u1pw
                hubAdmin = false,
                lastUpdated = INITIALTIMESTAMP,
                orgid = TESTORGS.head.orgId,
                updatedBy = "",
                username = TESTORGS.head.orgId + "/u1"))
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
                owner              = TESTUSERS.head.username,
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
                owner              = TESTUSERS.head.username,
                pattern            = "",
                publicKey          = "",
                regServices        = "",
                softwareVersions   = "",
                token              = "",
                userInput          = ""))
  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(description  = Option(""),
                     group        = 0L,
                     organization = TESTORGS.head.orgId,
                     lastUpdated  = INITIALTIMESTAMP,
                     name         = "ng0"),
        NodeGroupRow(description = Option(""),
                     group = 0L,
                     organization = TESTORGS.head.orgId,
                     lastUpdated = INITIALTIMESTAMP,
                     name = "ng1"))
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run((OrgsTQ ++= TESTORGS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
    
    val nodeGroup: Long = Await.result(DBCONNECTION.getDb.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS.head.name).map(_.group).result.head), AWAITDURATION)
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(NodeGroupAssignmentRow(group = nodeGroup,
                                 node = TESTNODES.head.id))
    
    Await.ready(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS), AWAITDURATION)
  
    //Http(URL + TESTORGS.head.orgId + "/users/u1").postData(write(PostPutUsersRequest("u1pw", admin = true, Option(false), "u1@hotmail.com"))).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestPostNodeToNodeGroup").delete), AWAITDURATION)
    
    DBCONNECTION.getDb.close()
  }
  
  test("POST /orgs/someorg/hagroup/ng0/nodes/n1 -- 404 not found - Bad Organization") {
    val response: HttpResponse[String] = Http(URL + "someorg" + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("POST /orgs/TestDeleteNodeFromNodeGroup/hagroup/somenodegroup/nodes/n1 -- 404 not found - Bad Node Group") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + "somenodegroup" + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("POST /orgs/TestDeleteNodeFromNodeGroup/hagroup/ng0/nodes/somenode -- 404 not found - Bad Node") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/somenode").method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.NOT_FOUND.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n0 -- 409 Conflict - Add the same node to the same node group twice") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng1/n0 -- 409 Conflict - Add an assigned node to a different node group") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.last.name + "/nodes/" + TESTNODES.head.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.ALREADY_EXISTS2.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n1 -- 403 access denied - Assigning a node owned by another user - u1") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode(TESTUSERS.last.username + ":u1pw"))).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.ACCESS_DENIED.intValue)
  }
  
  test("POST /orgs/TestPostNodeToNodeGroup/hagroups/ng0/n1 -- 201 OK - Default - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("post").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === HttpCode.POST_OK.intValue)
    
    val assignedNodes: Seq[(String, String, String)] = Await.result(DBCONNECTION.getDb.run(NodeGroupAssignmentTQ.join(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId)).on(_.group === _.group).sortBy(_._1.node.asc.nullsLast).map(records => {(records._2.name, records._1.node, records._2.organization)}).result), AWAITDURATION)
    assert(assignedNodes.size === 2)
    
    assert(assignedNodes.head._1 === TESTNODEGROUPS.head.name)
    assert(assignedNodes.head._2 === TESTNODES.head.id)
    assert(assignedNodes.head._3 === TESTNODEGROUPS.head.organization)
    
    assert(assignedNodes.last._1 === TESTNODEGROUPS.head.name)
    assert(assignedNodes.last._2 === TESTNODES.last.id)
    assert(assignedNodes.last._3 === TESTNODEGROUPS.head.organization)
    
    val changeRecords: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.getDb.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPostNodeToNodeGroup").sortBy(_.category.asc.nullsLast).result), AWAITDURATION)
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
}
