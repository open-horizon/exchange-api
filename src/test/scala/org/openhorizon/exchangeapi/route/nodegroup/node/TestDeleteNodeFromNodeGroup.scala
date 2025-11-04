package org.openhorizon.exchangeapi.route.nodegroup.node

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
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

class TestDeleteNodeFromNodeGroup extends AnyFunSuite with BeforeAndAfterAll {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  private val INITIALTIMESTAMP: String = ApiTime.nowUTC
  
  val TIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  
  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "TestDeleteNodeFromNodeGroup",
               lastUpdated        = INITIALTIMESTAMP,
               limits             = "",
               orgId              = "TestDeleteNodeFromNodeGroup",
               orgType            = "",
               tags               = None))
  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = TIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = TIMESTAMP,
                organization = TESTORGS.head.orgId,
                password     = None,
                username     = "u0"))
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
    Seq(NodeGroupRow(description  = Option(""),
                     group        = 0L,
                     organization = TESTORGS.head.orgId,
                     lastUpdated  = INITIALTIMESTAMP,
                     name         = "ng0"))
  
  
  
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                 (UsersTQ ++= TESTUSERS) andThen
                                 (NodesTQ ++= TESTNODES) andThen
                                 (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
  
    val nodeGroup: Long = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.name === TESTNODEGROUPS.head.name).map(_.group).result.head), AWAITDURATION)
    val TESTNODEGROUPASSIGNMENTS: Seq[NodeGroupAssignmentRow] =
      Seq(NodeGroupAssignmentRow(group = nodeGroup,
                                 node = TESTNODES.head.id),
          NodeGroupAssignmentRow(group = nodeGroup,
                                 node = TESTNODES.last.id))
                                 
    Await.ready(DBCONNECTION.run(NodeGroupAssignmentTQ ++= TESTNODEGROUPASSIGNMENTS), AWAITDURATION)
  }
  
  override def afterAll(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestDeleteNodeFromNodeGroup").delete andThen
                                       OrgsTQ.filter(_.orgid startsWith "TestDeleteNodeFromNodeGroup").delete), AWAITDURATION)
  }
  
  test("DELETE /orgs/someorg/hagroup/ng0/nodes/n1 -- 404 not found - Bad Organization") {
    val response: HttpResponse[String] = Http(URL + "someorg" + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.last.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("DELETE /orgs/TestDeleteNodeFromNodeGroup/hagroup/somenodegroup/nodes/n1 -- 404 not found - Bad Node Group") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + "somenodegroup" + "/nodes/" + TESTNODES.last.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("DELETE /orgs/TestDeleteNodeFromNodeGroup/hagroup/ng0/nodes/somenode -- 404 not found - Bad Node") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/somenode").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
    
    assert(response.code === StatusCodes.NotFound.intValue)
  }
  
  test("DELETE /orgs/TestDeleteNodeFromNodeGroup/hagroup/ng0/nodes/n0 -- 204 Deleted - Default - root") {
    val response: HttpResponse[String] = Http(URL + TESTORGS.head.orgId + "/hagroups/" + TESTNODEGROUPS.head.name + "/nodes/" + TESTNODES.head.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)
  
    assert(response.code === StatusCodes.NoContent.intValue)
  
    val nodeAssignments: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.join(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId)).on(_.group === _.group).map(_._1).result), AWAITDURATION)
    assert(nodeAssignments.size === 1)
    assert(nodeAssignments.head.node === TESTNODES(1).id)
  
    val changeRecords: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === TESTORGS.head.orgId).sortBy(_.category.asc.nullsLast).result), AWAITDURATION)
    assert(changeRecords.size === 2)
  
    assert(changeRecords.head.category  === ResChangeCategory.NODEGROUP.toString)
    assert(changeRecords.head.id        === TESTNODEGROUPS.head.name)
    assert(changeRecords.head.operation === ResChangeOperation.MODIFIED.toString)
    assert(changeRecords.head.orgId     ===  TESTORGS.head.orgId)
    assert(changeRecords.head.public    === "false")
    assert(changeRecords.head.resource  === ResChangeResource.NODEGROUP.toString)
  
    assert(changeRecords(1).category  === ResChangeCategory.NODE.toString)
    assert(changeRecords(1).id        === TESTNODES.head.name)
    assert(changeRecords(1).operation === ResChangeOperation.MODIFIED.toString)
    assert(changeRecords(1).orgId     === TESTORGS.head.orgId)
    assert(changeRecords(1).public    === "false")
    assert(changeRecords(1).resource  === ResChangeResource.NODE.toString)
  }
}
