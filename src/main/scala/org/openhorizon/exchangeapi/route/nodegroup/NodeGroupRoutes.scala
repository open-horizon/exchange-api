package org.openhorizon.exchangeapi.route.nodegroup

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, POST, PUT, Path}
import org.openhorizon.exchangeapi.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.auth.{AccessDeniedException, BadInputException, ResourceNotFoundException}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroup, NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignment, NodeGroupAssignmentRow, NodeGroupAssignmentTQ, PostPutNodeGroupsRequest}
import org.openhorizon.exchangeapi.table.node.{NodeRow, Nodes, NodesTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, AuthRoles, AuthenticationSupport, ExchMsg, HttpCode, OrgAndId, TNode, auth}
import org.postgresql.util.PSQLException
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.sql.Timestamp
import java.time.ZoneId
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.{Failure, Success}

@Path("/v1/orgs/{org}/hagroups")
@io.swagger.v3.oas.annotations.tags.Tag(name = "high availability node group")
trait NodeGroupRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def nodeGroupRoutes: Route =
    deleteNodeGroup() ~
    deleteNodeFromNodeGroup() ~
    getNodeGroup ~
    getAllNodeGroup ~
    putNodeGroup ~
    postNodeGroup ~
    postNodeToNodeGroup


  /* ====== DELETE /orgs/{org}/hagroups/{hagroup} ================================ */
  @DELETE
  @Path("{hagroup}")
  @Operation(description = "Deletes a Highly Available Node Group by name",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node identifier",
                                              in = ParameterIn.PATH,
                                              name = "hagroup")),
             responses = Array(new responses.ApiResponse(description = "deleted",
                                                         responseCode = "204"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404")),
             summary = "Deletes a Node Group")
  def deleteNodeGroup(): Route = (path("orgs" / Segment / "hagroups" / Segment) & delete) { (org, hagroup) =>
    logger.debug(s"Doing DELETE /orgs/$org/hagroups/$hagroup")
    val compositeId: String = OrgAndId(org, hagroup).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      complete({
        val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (ident.isAdmin)
            NodesTQ.getAllNodes(org)
          else
            NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
        val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.organization === org)
                     .filter(_.name === hagroup)
        
        val removeNodeGroup: DBIOAction[(Int, Option[Int], Seq[String], Int), NoStream, Effect.Read with Effect with Effect.Write] =
          for {
            nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
            
            _ <-
              if (nodeGroupAdmin.isEmpty)
                DBIO.failed(new ResourceNotFoundException())
              else if (!ident.isAdmin &&
                       nodeGroupAdmin.getOrElse(false))
                DBIO.failed(new AccessDeniedException())
              else
                DBIO.successful(())
            
            assignedNodesNotOwned <-
              Compiled(NodeGroupAssignmentTQ.join(nodeGroupQuery.map(_.group))
                                            .on(_.group === _)
                                            .map(_._1.node)
                                            .joinLeft(nodesQuery.map(_.id))
                                            .on(_ === _)
                                            .filter(_._2.isEmpty)
                                            .length)
                                            .result
            
            _ <-
              if (!assignedNodesNotOwned.equals(0))
                DBIO.failed(new AccessDeniedException())
              else
                DBIO.successful(())
            
            nodesToBeRemoved <-
              Compiled(NodeGroupAssignmentTQ.join(nodeGroupQuery.map(_.group)).on(_.group === _).map(_._1.node)).result
            
            numNodeGroupsRemoved <- Compiled(nodeGroupQuery).delete
            
            _ <-
              if (numNodeGroupsRemoved.equals(0))
                DBIO.failed(new ResourceNotFoundException())
              else
                DBIO.successful(())
            
            changes =
              nodesToBeRemoved.map(
                node =>
                  ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                    changeId = 0L,
                                    id = ("[^/]+$".r findFirstIn node).getOrElse(""), // Trim Organization prefix from Node identifier
                                    lastUpdated = changeTimestamp,
                                    operation = ResChangeOperation.MODIFIED.toString,
                                    orgId = org,
                                    public = "false",
                                    resource = ResChangeResource.NODE.toString)) :+
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = hagroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.DELETED.toString,
                                orgId = org,
                                public = "false",
                                resource = ResChangeResource.NODEGROUP.toString)
            
            changesInserted <- ResourceChangesTQ ++= changes
            
            _ <-
              if (changesInserted.isEmpty ||
                  !changesInserted.getOrElse(0).equals(changes.length))
                DBIO.failed(new IllegalStateException())
              else
                DBIO.successful(())
        } yield (assignedNodesNotOwned, changesInserted, nodesToBeRemoved, numNodeGroupsRemoved) // Yielding these for debugging, no functional reason.
        
        db.run(removeNodeGroup.transactionally.asTry).map({
          case Success(result) =>
              logger.debug("DELETE /orgs/" + org + "/hagroups/" + hagroup + " updated in changes table: " + result)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.deleted")))
          case Failure(t: AccessDeniedException) =>
            (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, t.getMessage))
          case Failure(t: ResourceNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, t.getMessage))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
  
  /* ====== DELETE /orgs/{org}/hagroups/{hagroup}/nodes/{node} ================================ */
  @DELETE
  @Path("{hagroup}/nodes/{node}")
  @Operation(description = "Deletes a Node from a Highly Available Node Group",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node Group identifier",
                                              in = ParameterIn.PATH,
                                              name = "hagroup"),
                                new Parameter(description = "Node identifier",
                                              in = ParameterIn.PATH,
                                              name = "node")),
             responses = Array(new responses.ApiResponse(description = "deleted",
                                                         responseCode = "204"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404")),
             summary = "Deletes a Node from a Node Group")
  def deleteNodeFromNodeGroup(): Route = (path("orgs" / Segment / "hagroups" / Segment / "nodes" / Segment) & delete) { (org, hagroup, node) =>
    logger.debug(s"Doing DELETE /orgs/$org/hagroups/$hagroup/nodes/$node")
    val compositeId: String = OrgAndId(org, node).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
        val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.name === hagroup)
                     .filter(_.organization === org)
        val changeRecords: Seq[ResourceChangeRow] =
          Seq(ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                changeId = 0L,
                                id = node,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = org,
                                public = "false",
                                resource = ResChangeResource.NODE.toString),
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = hagroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = org,
                                public = "false",
                                resource = ResChangeResource.NODEGROUP.toString))
        
        val deleteQuery: DBIOAction[(Option[Int], Int, Int), NoStream, Effect.Write] =
          for {
            changeRecordsInserted <- ResourceChangesTQ ++= changeRecords
            
            _ <-
              if (changeRecordsInserted.isEmpty ||
                  !changeRecordsInserted.getOrElse(0).equals(2))
                DBIO.failed(new IllegalStateException())
              else
                DBIO.successful(())
                
            nodeGroupsUpdated <-
              Compiled(nodeGroupQuery.map(_.lastUpdated))
                                     .update(fixFormatting(changeTimestamp.toInstant
                                                                          .atZone(ZoneId.of("UTC"))
                                                                          .withZoneSameInstant(ZoneId.of("UTC"))
                                                                          .toString))
            
            _ <-
              if (nodeGroupsUpdated.equals(0))
                DBIO.failed(new ResourceNotFoundException())
              else
                DBIO.successful(())
            
            nodeAssignmentsDeleted <- Compiled(NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group))
                                                                    .filter(_.node === compositeId))
                                                                    .delete
            
            _ <-
              if (nodeAssignmentsDeleted.equals(0))
                DBIO.failed(new ResourceNotFoundException())
              else
                DBIO.successful(())
          } yield (changeRecordsInserted, nodeAssignmentsDeleted, nodeGroupsUpdated)  // Yielding these for debugging, no functional reason.
        
        db.run(deleteQuery.transactionally.asTry).map({
          case Success(result) =>
            logger.debug("DELETE /orgs/" + org + "/hagroups/" + hagroup + "/nodes/" + node + " updated in changes table: " + result._1.getOrElse(-1))
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.node.deleted")))
          case Failure(e: IllegalStateException) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("changes.not.created", (org + "/" + hagroup + "/" + node))))
          case Failure(e: ResourceNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.node.not.found", (org + "/" + hagroup + "/" + node))))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
  
  /* ====== GET /orgs/{org}/hagroups/{hagroup} ================================ */
  @GET
  @Path("{hagroup}")
  @Operation(description = "Returns the Node Group along with the member nodes that the caller has permission to view.",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node Group identifier.",
                                              in = ParameterIn.PATH,
                                              name = "hagroup")),
             responses = Array(new responses.ApiResponse(
                                 content = Array(
                                   new Content(
                                     examples = Array(
                                       new ExampleObject(
                                         value =
"""{
  "nodeGroups": [
    {
      "name": "nodegroup",
      "members": [
        "node1",
        "node2",
        "node3"
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
             summary = "Lists all members of the specified Node Group (HA Group)")
  def getNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & get) { (org, hagroup) =>
    logger.debug(s"doing GET /orgs/$org/hagroups")
    exchAuth(TNode(OrgAndId(org, "#").toString), Access.READ) { ident =>
      complete({
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (ident.isAdmin ||
              ident.role.equals(AuthRoles.Agbot))
            NodesTQ.getAllNodes(org)
          else
            NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
        
        val getNodeGroup =
          for {
            nodeGroupWAssignments <- NodeGroupTQ.getAllNodeGroups(org).filter(_.name === hagroup) joinLeft NodeGroupAssignmentTQ.filter(_.node in nodesQuery.map(_.id)) on (_.group === _.group)
          } yield (nodeGroupWAssignments)
        
        db.run(Compiled(getNodeGroup.sortBy(_._2.map(_.node).getOrElse(""))).result.transactionally.asTry).map({
          case Success(result) =>
            if (result.nonEmpty)
              if (result.head._2.isDefined)
                (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(admin = result.head._1.admin,
                                                                      description =
                                                                        if (!ident.isAdmin && result.head._1.admin)
                                                                          ""
                                                                        else
                                                                          result.head._1.description.getOrElse(""),
                                                                      lastUpdated = result.head._1.lastUpdated,
                                                                      members = result.map(_._2.get.node.split("/")(1)),
                                                                      name = result.head._1.name))))
              else
                (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(admin = result.head._1.admin,
                                                                      description =
                                                                        if (!ident.isAdmin && result.head._1.admin)
                                                                          ""
                                                                        else
                                                                          result.head._1.description.getOrElse(""),
                                                                      lastUpdated = result.head._1.lastUpdated,
                                                                      members = Seq.empty[String],
                                                                      name = result.head._1.name)))) //node group with no members
            else
              (HttpCode.NOT_FOUND, GetNodeGroupsResponse(ListBuffer[NodeGroupResp]().toSeq)) //node group doesn't exist
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("api.internal.error")))
        })
      })
    }
  }
  
  /* ====== GET /orgs/{org}/hagroups ================================ */
  @GET
  @Path("")
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
  def getAllNodeGroup: Route = (path("orgs" / Segment / "hagroups") & get) { (org) =>
    logger.debug(s"doing GET /orgs/$org/hagroups")
    exchAuth(TNode(OrgAndId(org, "#").toString), Access.READ) { ident =>
      complete({
        val nodeGroupsQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.getAllNodeGroups(org).sortBy(_.name)
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (ident.isAdmin ||
              ident.role.equals(AuthRoles.Agbot))
            NodesTQ.getAllNodes(org)
          else
            NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
        
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
                                              if (!ident.isAdmin && nodeGroup.admin)
                                                ""
                                              else
                                                nodeGroup.description.getOrElse(""),
                                            lastUpdated = nodeGroup.lastUpdated,
                                            members = assignmentMap(nodeGroup.group).map(_.node.split("/")(1)),
                                            name = nodeGroup.name)
                else
                  response += NodeGroupResp(admin = nodeGroup.admin,
                                            description =
                                              if (!ident.isAdmin && nodeGroup.admin)
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
  }
  
  /* ====== PUT /orgs/{org}/hagroups/{hagroup} ================================ */
  @PUT
  @Path("{hagroup}")
  @Operation(description = "Replaces the list of nodes that belong to the specified node group with the list of nodes provided in the request body.",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node Group identifier.",
                                              in = ParameterIn.PATH,
                                              name = "hagroup")),
             requestBody = new RequestBody(
                             content = Array(
                               new Content(
                                 examples = Array(
                                   new ExampleObject(
                                     value =
"""{
  "members": [
    "node2",
    "node4",
    "node5"
  ],
  "description": "New Description"
}""")),
                                 mediaType = "application/json",
                                 schema = new Schema(implementation = classOf[PutNodeGroupsRequest]))),
                             required = true),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json",
                                                                                     schema = new Schema(implementation = classOf[ApiResponse]))),
                                                         description = "resource updated - response body:",
                                                         responseCode = "201"),
                               new responses.ApiResponse(description = "bad input",
                                                         responseCode = "400"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404"),
                               new responses.ApiResponse(description = "node already belongs to other group",
                                                         responseCode = "409")),
             summary = "Update the nodes that belong to an existing Node Group (HA Group)")
  def putNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & put & entity(as[PutNodeGroupsRequest])) { (org, hagroup, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$org/users/$hagroup")
    exchAuth(TNode(OrgAndId(org, "#").toString), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
          val members: Option[Seq[String]] =
            if (reqBody.members.isEmpty)
              None
            else if (reqBody.members.get.isEmpty)
              Option(Seq())
            else
              Option(reqBody.members.get.distinct.map(node => OrgAndId(org, node).toString)) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
          
          val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
            NodeGroupTQ.filter(_.organization === org)
                       .filter(_.name === hagroup)
          val nodesQuery: Query[Nodes, NodeRow, Seq] =
            if (ident.isAdmin ||
                ident.isSuperUser)
              NodesTQ.getAllNodes(org)
            else
              NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
          
          val syncNodeGroup: DBIOAction[(Seq[String], Option[Int], Seq[String], Seq[String]), NoStream, Effect.Write with Effect with Effect.Read] =
            for {
              nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
              
              _ <-
                if (nodeGroupAdmin.isEmpty)
                  DBIO.failed(new ResourceNotFoundException())
                else if (!ident.isAdmin &&
                         nodeGroupAdmin.getOrElse(false))
                  DBIO.failed(new AccessDeniedException())
                else
                  DBIO.successful(())
            
              numNodeGroupsUpdated <-
                if (reqBody.description.nonEmpty)
                  Compiled(nodeGroupQuery.map(nodeGroup => (nodeGroup.description, nodeGroup.lastUpdated)))
                                .update((reqBody.description,
                                         fixFormatting(changeTimestamp.toInstant
                                                                      .atZone(ZoneId.of("UTC"))
                                                                      .withZoneSameInstant(ZoneId.of("UTC"))
                                                                      .toString)))
                else
                  Compiled(nodeGroupQuery.map(_.lastUpdated))
                                .update(fixFormatting(changeTimestamp.toInstant
                                                                     .atZone(ZoneId.of("UTC"))
                                                                     .withZoneSameInstant(ZoneId.of("UTC"))
                                                                     .toString))
  
              _ <-
                if (!numNodeGroupsUpdated.equals(1))
                  DBIO.failed(new ResourceNotFoundException())
                else
                  DBIO.successful(())
  
              group <- Compiled(nodeGroupQuery.map(_.group)).result.head
  
              nodeGroupAssignments: Query[NodeGroupAssignment, NodeGroupAssignmentRow, Seq] =
                NodeGroupAssignmentTQ.filter(_.group === group)
  
              assignedNodesNotOwned <- Compiled(nodeGroupAssignments.map(_.node).joinLeft(nodesQuery.map(_.id)).on(_ === _).filter(_._2.isEmpty).map(_._1).length).result
  
              _ <-
                if (!assignedNodesNotOwned.equals(0))
                  DBIO.failed(new AccessDeniedException())
                else
                  DBIO.successful(())
  
              nodesToBeReplaced <-
                if (members.nonEmpty)
                  Compiled(nodeGroupAssignments.map(_.node)).result
                else
                  DBIO.successful(Seq())
  
              numAssignmentsRemoved <-
                if (members.nonEmpty)
                  Compiled(nodeGroupAssignments).delete
                else
                  DBIO.successful(0)
  
              nodesToAssign <- Compiled(nodesQuery.filter(_.id inSet members.getOrElse(Seq()))
                                                  .map(_.id))
                                                  .result
                                                  .map(nodes =>
                                                    nodes.map(node =>
                                                      NodeGroupAssignmentRow(group = group,
                                                                             node = node)))
  
              // Authorization check for all nodes in the request body.
              _ <-
                if (members.nonEmpty &&
                    !(nodesToAssign.sizeIs == members.get.length))
                  DBIO.failed(new AccessDeniedException())
                else
                  DBIO.successful(())
  
              assignedNodes <- (NodeGroupAssignmentTQ returning NodeGroupAssignmentTQ.map(_.node)) ++= nodesToAssign
  
              _ <-
                if (nodesToAssign.isEmpty ||
                    (nodesToAssign.nonEmpty &&
                     assignedNodes.sizeIs == members.get.length))
                  DBIO.successful(())
                else
                  DBIO.failed(new IllegalStateException())
  
              nodesChanged = (assignedNodes ++ nodesToBeReplaced).distinct
  
              changes =
                nodesChanged.map(node =>
                  ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                    changeId = 0L,
                                    id = ("[^/]+$".r findFirstIn node).getOrElse(""), // Trim Organization prefix from Node identifier
                                    lastUpdated = changeTimestamp,
                                    operation = ResChangeOperation.MODIFIED.toString,
                                    orgId = org,
                                    public = "false",
                                    resource = ResChangeResource.NODE.toString)) :+
                  ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                    changeId = 0L,
                                    id = hagroup,
                                    lastUpdated = changeTimestamp,
                                    operation = ResChangeOperation.MODIFIED.toString,
                                    orgId = org,
                                    public = "false",
                                    resource = ResChangeResource.NODEGROUP.toString)
  
              changesInserted <- ResourceChangesTQ ++= changes
  
              _ <-
                if (changesInserted.isEmpty ||
                    !changesInserted.getOrElse(0).equals(changes.length))
                  DBIO.failed(new IllegalStateException())
                else
                  DBIO.successful(())
          } yield (assignedNodes, changesInserted, nodesChanged, nodesToBeReplaced)  // Yielding these for debugging, no functional reason.
          
          db.run(syncNodeGroup.transactionally.asTry).map({
            case Success(result) =>
              logger.debug(s"PUT /orgs/$org/hagroups/$hagroup update successful")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.updated.successfully", hagroup, org)))
            case Failure(t: ResourceNotFoundException) =>
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, t.getMessage))
            case Failure(t: BadInputException) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, t.getMessage))
            case Failure(t: auth.AlreadyExistsException) =>
              (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, t.getMessage))
            case Failure(t: AccessDeniedException) =>
              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, t.getMessage))
            case Failure(e: PSQLException) =>
              if (e.getServerErrorMessage.getConstraint.equals("node_group_idx"))
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", (org + "/" + hagroup))))
              else if (e.getServerErrorMessage.getConstraint.equals("fk_organization"))
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", org)))
              else if (e.getServerErrorMessage.getConstraint.equals("pk_node_group_assignment"))
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict")))
              else
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.updated", (org + "/" + hagroup), e.toString)))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", hagroup, org, t.getMessage)))
          })
        })
      }
    }
  }
  
  /* ====== POST /orgs/{org}/hagroups/{hagroup} ================================ */
  @POST
  @Path("{hagroup}")
  @Operation(description = "Creates the list of nodes that belong to the specified node group with the list of nodes provided in the request body.",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node Group identifier.",
                                              in = ParameterIn.PATH,
                                              name = "hagroup")),
             requestBody = new RequestBody(
                             content = Array(
                               new Content(
                                 examples = Array(
                                   new ExampleObject(
                                     value =
"""{
  "members": [
    "node2",
    "node4",
    "node5"
  ],
  "description": "New Description"
}""")),
                                 mediaType = "application/json",
                                 schema = new Schema(implementation = classOf[PutNodeGroupsRequest]))),
                             required = true),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json",
                                                                                     schema = new Schema(implementation = classOf[ApiResponse]))),
                                                         description = "resource created - response body:",
                                                         responseCode = "201"),
                               new responses.ApiResponse(description = "bad input",
                                                         responseCode = "400"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied: node isn't owned by caller",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404"),
                               new responses.ApiResponse(description = "node already belongs to other group",
                                                         responseCode = "409")),
             summary = "Insert the nodes that belong to an existing Node Group (HA Group)")
  def postNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & post & entity(as[PostPutNodeGroupsRequest])) { (org, hagroup, reqBody) =>
    logger.debug(s"Doing POST /orgs/$org/hagroups/$hagroup")
    val compositeId: String = OrgAndId(org, hagroup).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val admin: Boolean = ident.isAdmin
          val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
          val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
            NodeGroupTQ.filter(_.organization === org)
                       .filter(_.name === hagroup)
          val nodesQuery: Query[Nodes, NodeRow, Seq] =
            if (admin)
              NodesTQ.getAllNodes(org)
            else
              NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
          val members: Seq[String] =
            if (reqBody.members.isEmpty ||
                reqBody.members.get.isEmpty)
              Seq()
            else
              reqBody.members.get.distinct.map(node => OrgAndId(id = node, org = org).toString) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
          
          val createNodeGroup: DBIOAction[Unit, NoStream, Effect.Write with Effect.Read with Effect] =
            for {
              // Automatically return the assigned node group identifier when creating a new Node Group.
              group <-
                (NodeGroupTQ returning NodeGroupTQ.map(_.group)) +=
                  NodeGroupRow(admin = admin,
                               description = reqBody.description,
                               group = 0L,
                               organization = org,
                               lastUpdated = fixFormatting(changeTimestamp.toInstant
                                                                          .atZone(ZoneId.of("UTC"))
                                                                          .withZoneSameInstant(ZoneId.of("UTC"))
                                                                          .toString),
                               name = hagroup)
              
              /*
               * Grab Nodes that exist based off what was provided in the request body filtering on ownership if
               * applicable. Convert records into something That can be stored in NodeGroupAssignment.
               */
              nodesToAssign <-
                Compiled(nodesQuery.filter(_.id inSet members)
                                   .map(_.id))
                                   .result
                                   .map(nodes =>
                                     nodes.map(node =>
                                       NodeGroupAssignmentRow(group = group,
                                                              node = node)))
              
              // Authorization check for all nodes in the request body.
              _ <-
                if (!(nodesToAssign.sizeIs == members.length))
                  DBIO.failed(new AccessDeniedException())
                else
                  DBIO.successful(())
              
              /*
               * We do not need to check for duplicate assignments beforehand because the primary key (node) will
               * handle that for us. Any already assigned nodes will trigger a primary key violation which will result
               * in a database transaction rollback, and a PSQL exception thrown.
               */
              assignedNodes <- (NodeGroupAssignmentTQ returning NodeGroupAssignmentTQ.map(_.node)) ++= nodesToAssign
              
              _ <-
                if (nodesToAssign.isEmpty ||
                    (nodesToAssign.nonEmpty &&
                     assignedNodes.sizeIs == members.length))
                  DBIO.successful(())
                else
                  DBIO.failed(new IllegalStateException())
              
              changes =
                assignedNodes.map(node =>
                  ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                    changeId = 0L,
                                    id =  ("[^/]+$".r findFirstIn node).getOrElse(""), // Trim Organization prefix from Node identifier
                                    lastUpdated = changeTimestamp,
                                    operation = ResChangeOperation.MODIFIED.toString,
                                    orgId = org,
                                    public = "false",
                                    resource = ResChangeResource.NODE.toString)) :+
                ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                  changeId = 0L,
                                  id = hagroup,
                                  lastUpdated = changeTimestamp,
                                  operation = ResChangeOperation.CREATED.toString,
                                  orgId = org,
                                  public = "false",
                                  resource = ResChangeResource.NODEGROUP.toString)
              
              changesInserted <- ResourceChangesTQ ++= changes
              
              _ <-
                if (changesInserted.isEmpty ||
                    !changesInserted.getOrElse(0).equals(changes.length))
                  DBIO.failed(new IllegalStateException())
                else
                  DBIO.successful(())
          } yield (assignedNodes, nodesToAssign)  // Yielding these for debugging, no functional reason.

          db.run(createNodeGroup.transactionally.asTry).map({
            case Success(result) =>
              logger.debug(s"POST /orgs/$org/hagroups/$hagroup result: $result")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.created", compositeId)))
            case Failure(e: AccessDeniedException) =>
              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("access.denied.no.auth", ident.identityString, "WRITE_ALL_NODES", "")))
            case Failure(e: IllegalStateException) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.records.not.inserted")))
            case Failure(e: PSQLException) =>
              if (e.getServerErrorMessage.getConstraint.equals("node_group_idx"))
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", (org + "/" + hagroup))))
              else if (e.getServerErrorMessage.getConstraint.equals("fk_organization"))
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", org)))
              else if (e.getServerErrorMessage.getConstraint.equals("pk_node_group_assignment"))
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict")))
              else
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", compositeId, e.toString)))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }
  
  /* ====== POST /orgs/{org}/hagroups/{hagroup}/nodes/{node} ================================ */
  @POST
  @Path("{hagroup}/nodes/{node}")
  @Operation(description = "Appends to the list of nodes that belong to the specified node group.",
             parameters = Array(new Parameter(description = "Organization identifier.",
                                              in = ParameterIn.PATH,
                                              name = "org"),
                                new Parameter(description = "Node Group identifier.",
                                              in = ParameterIn.PATH,
                                              name = "hagroup"),
                                new Parameter(description = "Node Group identifier.",
                                              in = ParameterIn.PATH,
                                              name = "node")),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json",
                                                                                     schema = new Schema(implementation = classOf[ApiResponse]))),
                                                         description = "resource created - response body:",
                                                         responseCode = "201"),
                               new responses.ApiResponse(description = "bad input",
                                                         responseCode = "400"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied: node isn't owned by caller",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404"),
                               new responses.ApiResponse(description = "node already belongs to other group",
                                                         responseCode = "409")),
             summary = "Insert a Node into a High Availablity Node Group")
  def postNodeToNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment / "nodes" / Segment) & post) { (org, hagroup, node) =>
    logger.debug(s"Doing POST /orgs/$org/hagroups/$hagroup/nodes/$node")
    val compositeId: String = OrgAndId(org, node).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      complete({
        val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (ident.isAdmin ||
              ident.role.equals(AuthRoles.Agbot))
            NodesTQ.getAllNodes(org)
          else
            NodesTQ.getAllNodes(org).filter(_.owner === ident.identityString)
        val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.name === hagroup)
                     .filter(_.organization === org)
        val changeRecords: Seq[ResourceChangeRow] =
          Seq(ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                changeId = 0L,
                                id = node,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = org,
                                public = "false",
                                resource = ResChangeResource.NODE.toString),
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = hagroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = org,
                                public = "false",
                                resource = ResChangeResource.NODEGROUP.toString))
          
        val insertNodeAssignment: DBIOAction[(Option[Int], Int, Option[Long], Int, Option[String]), NoStream, Effect.Read with Effect with Effect.Write] =
          for {
            nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
            
            _ <-
              if (nodeGroupAdmin.isEmpty)
                DBIO.failed(new ResourceNotFoundException())
              else if (!ident.isAdmin &&
                       nodeGroupAdmin.getOrElse(false))
                DBIO.failed(new AccessDeniedException())
              else
                DBIO.successful(())
            
            assignedNodesNotOwned <-
              Compiled(NodeGroupAssignmentTQ.join(nodeGroupQuery.map(_.group))
                                            .on(_.group === _)
                                            .map(_._1.node)
                                            .joinLeft(nodesQuery.map(_.id))
                                            .on(_ === _)
                                            .filter(_._2.isEmpty)
                                            .length).result
            
            _ <-
              if (!assignedNodesNotOwned.equals(0))
                DBIO.failed(new AccessDeniedException())
              else
                DBIO.successful(())
            
            priorAssignment <- Compiled(NodeGroupAssignmentTQ.filter(_.node === compositeId)
                                                             .map(_.node)
                                                             .distinct).result.headOption
            
            _ <-
              if (priorAssignment.nonEmpty)
                DBIO.failed(new auth.AlreadyExistsException())
              else
                DBIO.successful(())
            
            changeRecordsInserted <- ResourceChangesTQ ++= changeRecords
            
            _ <-
              if (changeRecordsInserted.isEmpty ||
                  !changeRecordsInserted.getOrElse(0).equals(2))
                DBIO.failed(new IllegalStateException())
              else
                DBIO.successful(())
            
            nodeGroupsUpdated <-
              Compiled(nodeGroupQuery.filterIf(!ident.isAdmin)(_.admin === false)
                                     .map(_.lastUpdated))
                                     .update(fixFormatting(changeTimestamp.toInstant
                                                                          .atZone(ZoneId.of("UTC"))
                                                                          .withZoneSameInstant(ZoneId.of("UTC"))
                                                                          .toString))
            
            _ <-
              if (nodeGroupsUpdated.equals(0))
                DBIO.failed(new ResourceNotFoundException())
              else
                DBIO.successful(())
            
            nodeGroupID <- Compiled(nodeGroupQuery.filterIf(!ident.isAdmin)(_.admin === false)
                                                  .map(_.group)).result.headOption
            
            nodeAssignmentsInserted <-
              NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = nodeGroupID.get,
                                                              node = compositeId)
            
            _ <-
              if (!nodeAssignmentsInserted.equals(1))
                DBIO.failed(new IllegalStateException())
              else
                DBIO.successful(())
          } yield (changeRecordsInserted, nodeAssignmentsInserted, nodeGroupID, nodeGroupsUpdated, priorAssignment) // Yielding these for debugging, no functional reason.
        
        db.run(insertNodeAssignment.transactionally.asTry).map({
          case Success(result) =>
            logger.debug("DELETE /orgs/" + org + "/hagroups/" + hagroup + "/nodes/" + node + " updated in changes table: " + result._1.getOrElse(-1))
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.node.inserted")))
          case Failure(e: auth.AccessDeniedException) =>
            (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.group.access.denied")))
          case Failure(e: auth.AlreadyExistsException) =>
            (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.node.conflict", (org + "/" + node))))
          case Failure(e: IllegalStateException) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("changes.not.created", (org + "/" + hagroup + "/" + node))))
          case Failure(e: ResourceNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", (org + "/" + hagroup))))
          case Failure(e: PSQLException) =>
            if (e.getServerErrorMessage.getConstraint.equals("fk_node"))
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            else
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", compositeId, e.toString)))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
}
