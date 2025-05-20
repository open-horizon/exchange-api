package org.openhorizon.exchangeapi.route.nodegroup

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.{NodeRow, Nodes, NodesTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroup, NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{org}/hagroups")
@io.swagger.v3.oas.annotations.tags.Tag(name = "high availability node group")
trait NodeGroups extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{org}/hagroups ================================ */
  @GET
  @Operation(description = "Returns all Node Groups in this org along with the member nodes that the caller has permission to view.",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org")),
             responses = Array(new responses.ApiResponse(
                                 content = Array(
                                     new Content(
                                       examples = Array(
                                         new ExampleObject(
                                           value =
"""{
  "nodeGroups": [
    {
      "name": "nodegroup1",
      "members": [
        "node1",
        "node2",
        "node3"
      ],
      "lastUpdated": "2020-02-05T20:28:14.469Z[UTC]"
    },
    {
      "name": "nodegroup2",
      "members": [
        "nodeA",
        "nodeB",
        "nodeC"
      ],
      "lastUpdated": "2020-02-05T20:28:14.469Z[UTC]"
    }
  ]
}""")),
                                     mediaType = "application/json",
                                     schema = new Schema(implementation = classOf[GetNodeGroupsResponse]))),
                                 description = "response body",
                                 responseCode = "200"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404")),
             summary = "Lists all members of all Node Groups (HA Groups)")
  def getNodeGroups(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"GET /orgs/$organization/hagroups - By ${identity.resource}:${identity.role}")
      complete({
        val nodeGroupsQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.getAllNodeGroups(organization).sortBy(_.name)
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (identity.isOrgAdmin ||
              identity.isAgbot)
            NodesTQ.getAllNodes(organization)
          else
            NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier)
        
        val queries: DBIOAction[(Seq[NodeGroupRow], Seq[NodeGroupAssignmentRow]), NoStream, Effect.Read] =
          for {
            nodeGroups <- Compiled(nodeGroupsQuery).result
            
            nodeGroupAssignments <-
              Compiled(NodeGroupAssignmentTQ.filter(_.node in nodesQuery.map(_.id))
                                            .filter(_.group in nodeGroupsQuery.map(_.group))
                                            .sortBy(a => (a.group, a.node))).result
          } yield (nodeGroups, nodeGroupAssignments)
        
        db.run(queries.transactionally.asTry).map({
          case Success(result) =>
            if (result._1.nonEmpty) {
              val assignmentMap: Map[Long, Seq[NodeGroupAssignmentRow]] = result._2.groupBy(_.group)
              val response: ListBuffer[NodeGroupResp] = ListBuffer[NodeGroupResp]()
              for (nodeGroup <- result._1) {
                if (assignmentMap.contains(nodeGroup.group))
                  response += NodeGroupResp(admin = nodeGroup.admin,
                                            description =
                                              if (!identity.isOrgAdmin && nodeGroup.admin)
                                                ""
                                              else
                                                nodeGroup.description.getOrElse(""),
                                            lastUpdated = nodeGroup.lastUpdated,
                                            members = assignmentMap(nodeGroup.group).map(_.node.split("/")(1)),
                                            name = nodeGroup.name)
                else
                  response += NodeGroupResp(admin = nodeGroup.admin,
                                            description =
                                              if (!identity.isOrgAdmin && nodeGroup.admin)
                                                ""
                                              else
                                                nodeGroup.description.getOrElse(""),
                                            lastUpdated = nodeGroup.lastUpdated,
                                            members = Seq.empty[String],
                                            name = nodeGroup.name) //node group with no assignments
              }
              (HttpCode.OK, GetNodeGroupsResponse(response.toSeq))
            }
            else
              (HttpCode.NOT_FOUND, GetNodeGroupsResponse(ListBuffer[NodeGroupResp]().toSeq)) //no node groups in org
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("api.internal.error")))
        })
      })
    }
  
  def nodeGroups(identity: Identity2): Route =
    path("orgs" / Segment / "hagroups") {
      organization =>
        get {
          exchAuth(TNode(OrgAndId(organization, "#").toString), Access.READ, validIdentity = identity) {
            _ =>
              getNodeGroups(identity, organization)
          }
        }
    }
}
