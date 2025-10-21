package org.openhorizon.exchangeapi.route.nodegroup

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.table
import org.json4s.DefaultFormats
import org.openhorizon.exchangeapi.auth.{Password, Role}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ, NodeGroups}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, ApiUtils, Configuration, DatabaseConnection}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funsuite.AnyFunSuite
import scalaj.http.{Http, HttpResponse}
import slick.dbio.{Effect, NoStream}
import slick.jdbc
import slick.jdbc.PostgresProfile.api.{anyToShapedValue, columnExtensionMethods, columnToOrdered, longColumnType, queryDeleteActionExtensionMethods, queryInsertActionExtensionMethods, streamableQueryActionExtensionMethods, stringColumnExtensionMethods, stringColumnType}
import slick.sql.FixedSqlAction

import java.time.{Instant, ZoneId}
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.math.Ordered.orderingToOrdered


class TestDeleteNodeGroup extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {
  private val ACCEPT: (String, String) = ("Content-Type", "application/json")
  private val CONTENT: (String, String) = ACCEPT
  private val ROOTAUTH: (String, String) = ("Authorization", "Basic " + ApiUtils.encode(Role.superUser + ":" + (try Configuration.getConfig.getString("api.root.password") catch { case _: Exception => "" })))
  private val URL: String = sys.env.getOrElse("EXCHANGE_URL_ROOT", "http://localhost:8080") + "/v1/orgs/"
  private val DBCONNECTION: jdbc.PostgresProfile.api.Database = DatabaseConnection.getDatabase
  private val AWAITDURATION: Duration = 15.seconds
  implicit val formats: DefaultFormats.type = DefaultFormats // Brings in default date formats etc.
  
  private val INITIALTIMESTAMP: Instant = ApiTime.nowUTCTimestamp
  private val INITIALTIMESTAMPSTRING: String = INITIALTIMESTAMP.toString
  
  private val TESTNODEGROUPS: Seq[NodeGroupRow] =
    Seq(NodeGroupRow(admin = false,
                     description   = Option(""),
                     group         = 0L,
                     organization  = "TestDeleteNodeGroup",
                     lastUpdated   = INITIALTIMESTAMPSTRING,
                     name          = "king"),
        NodeGroupRow(admin = false,
                     description   = Option(""),
                     group         = 0L,
                     organization  = "TestDeleteNodeGroup1",
                     lastUpdated   = INITIALTIMESTAMPSTRING,
                     name          = "queen"))
  private val TESTUSERS: Seq[UserRow] = {
    Seq(UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = true,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestDeleteNodeGroup",
                password     = Option(Password.hash("admin1pw")),
                username     = "admin1"),
        UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestDeleteNodeGroup",
                password     = Option(Password.hash("u1pw")),
                username     = "u1"),
        UserRow(createdAt    = INITIALTIMESTAMP,
                isHubAdmin   = false,
                isOrgAdmin   = false,
                modifiedAt   = INITIALTIMESTAMP,
                organization = "TestDeleteNodeGroup",
                password     = Option(Password.hash("u2pw")),
                username     = "u2"))
  }
  private val TESTORGS: Seq[OrgRow] =
    Seq(OrgRow(description        = "T",
               heartbeatIntervals = "",
               label              = "TestDeleteNodeGroup",
               lastUpdated        = INITIALTIMESTAMPSTRING,
               limits             = "",
               orgId              = "TestDeleteNodeGroup",
               orgType            = "",
               tags               = None),
        OrgRow(description        = "",
               heartbeatIntervals = "",
               label              = "TestDeleteNodeGroup1",
               lastUpdated        = INITIALTIMESTAMPSTRING,
               limits             = "",
               orgId              = "TestDeleteNodeGroup1",
               orgType            = "",
               tags               = None))
  private val TESTNODES: Seq[NodeRow] =
    Seq(NodeRow(arch               = "",
                id                 = TESTORGS.head.orgId + "/node0",
                heartbeatIntervals = "",
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
                lastHeartbeat      = Option(ApiTime.nowUTC),
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
  
  // Build test harness.
  override def beforeAll(): Unit = {
    Await.ready(DBCONNECTION.run((OrgsTQ ++= TESTORGS) andThen
                                       (UsersTQ ++= TESTUSERS) andThen
                                       (NodesTQ ++= TESTNODES) andThen
                                       (NodeGroupTQ ++= TESTNODEGROUPS)), AWAITDURATION)
  }
  
  // Teardown testing harness and cleanup.
  override def afterAll(): Unit = {
     Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestDeleteNodeGroup").delete andThen
                                        OrgsTQ.filter(_.orgid startsWith "TestDeleteNodeGroup").delete), AWAITDURATION)
  }
  
  override def afterEach(): Unit = {
    Await.ready(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId startsWith "TestPutNodeGroup").delete), AWAITDURATION)
  }
  
  // Node Groups that are dynamically needed, specific to the test case.
  def fixtureNodeGroups(testCode: Seq[NodeGroupRow] => Any, testData: Seq[NodeGroupRow]): Any = {
    var nodeGroups: Seq[NodeGroupRow] = Seq()
    try {
      nodeGroups = Await.result(DBCONNECTION.run((NodeGroupTQ returning NodeGroupTQ) ++= testData), AWAITDURATION)
      testCode(nodeGroups)
    }
    finally
      Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.group inSet nodeGroups.map(_.group)).delete), AWAITDURATION)
  }
  
  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/randomgroup -- 404 not found - bad group - root") {
    val response: HttpResponse[String] = Http(URL + "TestDeleteNodeGroup/hagroups/randomgroup").method("delete").headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === StatusCodes.NotFound.intValue)
  }

  test("DELETE /orgs/somerandomorg/hagroups/king -- 404 not found - bad organization - root") {
    val response: HttpResponse[String] = Http(URL + "somerandomorg/hagroups/king").method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
    info("Code: " + response.code)
    info("Body: " + response.body)

    assert(response.code === StatusCodes.NotFound.intValue)
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/TestDeleteNodeGroup_ng0 -- 204 deleted - default - root") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestDeleteNodeGroup_ng0",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES.head.id)), AWAITDURATION)
        
        val response: HttpResponse[String] = Http(URL + TESTNODEGROUP.head.organization + "/hagroups/" + TESTNODEGROUP.head.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(ROOTAUTH).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
        
        assert(response.code === StatusCodes.NoContent.intValue)
        
        val nodeGroups: Seq[NodeGroupRow] = Await.result(DBCONNECTION.run(NodeGroupTQ.filter(_.organization === TESTORGS.head.orgId).result), AWAITDURATION)
        assert(nodeGroups.sizeIs == 1)
  
        assert(nodeGroups.head.organization === TESTNODEGROUPS.head.organization)
        assert(nodeGroups.head.lastUpdated === TESTNODEGROUPS.head.lastUpdated)
        assert(nodeGroups.head.name === TESTNODEGROUPS.head.name)
        
        val assignedNodes: Seq[NodeGroupAssignmentRow] = Await.result(DBCONNECTION.run(NodeGroupAssignmentTQ.filter(_.group === assignedTestNodeGroups.head.group).result), AWAITDURATION)
        assert(assignedNodes.sizeIs == 0)
        
        val changes: Seq[ResourceChangeRow] = Await.result(DBCONNECTION.run(ResourceChangesTQ.filter(_.orgId === TESTNODEGROUP.head.organization).sortBy(change => (change.category.asc.nullsLast, change.id.asc.nullsLast)).result), AWAITDURATION)
        assert(changes.sizeIs == 2)
  
        assert(changes.head.category === ResChangeCategory.NODEGROUP.toString)
        assert(changes.head.id === TESTNODEGROUP.head.name)
        assert(INITIALTIMESTAMP < changes.head.lastUpdated)
        assert(changes.head.operation === ResChangeOperation.DELETED.toString)
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
      }, TESTNODEGROUP)
  }

  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/TestDeleteNodeGroup_ng1 -- 403 access denied - attempt to delete node group without ownership- user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(admin = false,
                       description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestDeleteNodeGroup_ng1",
                       organization = TESTORGS.head.orgId))
  
    fixtureNodeGroups(
      assignedTestNodeGroups => {
        Await.ready(DBCONNECTION.run(
          NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                          node = TESTNODES.head.id)), AWAITDURATION)
      
        val response: HttpResponse[String] = Http(URL + TESTNODEGROUP.head.organization + "/hagroups/" + TESTNODEGROUP.head.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestDeleteNodeGroup/u1" + ":" + "u1pw"))).asString
        info("Code: " + response.code)
        info("Body: " + response.body)
      
        assert(response.code === StatusCodes.Forbidden.intValue)
      }, TESTNODEGROUP)
  }
  
  test("DELETE /orgs/TestDeleteNodeGroup/hagroups/TestDeleteNodeGroup_ng1 -- 403 access denied - attempt to delete admin node group that contains my node - user") {
    val TESTNODEGROUP: Seq[NodeGroupRow] =
      Seq(NodeGroupRow(admin = true,
                       description = None,
                       group = 0L,
                       lastUpdated = INITIALTIMESTAMPSTRING,
                       name = "TestDeleteNodeGroup_ng2",
                       organization = TESTORGS.head.orgId))
    
    fixtureNodeGroups(assignedTestNodeGroups => {
      Await.ready(DBCONNECTION.run(NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = assignedTestNodeGroups.head.group,
                                                                                         node = TESTNODES(3).id)), AWAITDURATION)
      
      val response: HttpResponse[String] = Http(URL + TESTNODEGROUP.head.organization + "/hagroups/" + TESTNODEGROUP.head.name).method("delete").headers(CONTENT).headers(ACCEPT).headers(("Authorization", "Basic " + ApiUtils.encode("TestDeleteNodeGroup/u1" + ":" + "u1pw"))).asString
      info("Code: " + response.code)
      info("Body: " + response.body)
      
      assert(response.code === StatusCodes.Forbidden.intValue)
    }, TESTNODEGROUP)
  }
}
