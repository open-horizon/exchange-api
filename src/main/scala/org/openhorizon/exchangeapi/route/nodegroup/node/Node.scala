package org.openhorizon.exchangeapi.route.nodegroup.node

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.auth.{Access, AccessDeniedException, AlreadyExistsException, AuthRoles, AuthenticationSupport, Identity, Identity2, OrgAndId, ResourceNotFoundException, TNode}
import org.openhorizon.exchangeapi.route.node.Nodes
import org.openhorizon.exchangeapi.table.node.Nodes
import org.openhorizon.exchangeapi.table.node.{NodeRow, NodesTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignment, NodeGroupAssignmentRow, NodeGroupAssignmentTQ}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroup, NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, HttpCode}
import org.postgresql.util.PSQLException
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Aliases

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{org}/hagroups/{hagroup}/nodes/{node}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "high availability node group")
trait Node extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== DELETE /orgs/{org}/hagroups/{hagroup}/nodes/{node} ================================ */
  @DELETE
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
  def deleteNodeFromNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                              @Parameter(hidden = true) identity: Identity2,
                              @Parameter(hidden = true) node: String,
                              @Parameter(hidden = true) organization: String,
                              @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        val changeTimestamp: Instant = ApiTime.nowUTCTimestamp
        val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.name === highAvailabilityGroup)
                     .filter(_.organization === organization)
        val changeRecords: Seq[ResourceChangeRow] =
          Seq(ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                changeId = 0L,
                                id = node,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = organization,
                                public = "false",
                                resource = ResChangeResource.NODE.toString),
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = highAvailabilityGroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = organization,
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
                                     .update(changeTimestamp.toString)
            
            _ <-
              if (nodeGroupsUpdated.equals(0))
                DBIO.failed(ResourceNotFoundException())
              else
                DBIO.successful(())
            
            nodeAssignmentsDeleted <- Compiled(NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group))
                                                                    .filter(_.node === resource))
                                                                    .delete
            
            _ <-
              if (nodeAssignmentsDeleted.equals(0))
                DBIO.failed(ResourceNotFoundException())
              else
                DBIO.successful(())
          } yield (changeRecordsInserted, nodeAssignmentsDeleted, nodeGroupsUpdated)  // Yielding these for debugging, no functional reason.
        
        db.run(deleteQuery.transactionally.asTry).map({
          case Success(result) =>
            logger.debug("DELETE /orgs/" + organization + "/hagroups/" + highAvailabilityGroup + "/nodes/" + node + " updated in changes table: " + result._1.getOrElse(-1))
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.node.deleted")))
          case Failure(e: IllegalStateException) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("changes.not.created", (organization + "/" + highAvailabilityGroup + "/" + node))))
          case Failure(e: ResourceNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.node.not.found", (organization + "/" + highAvailabilityGroup + "/" + node))))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== POST /orgs/{org}/hagroups/{hagroup}/nodes/{node} ================================ */
  @POST
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
  def postNodeToNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                          @Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) node: String,
                          @Parameter(hidden = true) organization: String,
                          @Parameter(hidden = true) resource: String): Route =
    post {
      Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
      complete({
        val changeTimestamp: Instant = ApiTime.nowUTCTimestamp
        val nodesQuery: Query[org.openhorizon.exchangeapi.table.node.Nodes, NodeRow, Seq] =
          if (identity.isOrgAdmin ||
              identity.isSuperUser ||
              identity.isAgbot)
            NodesTQ.getAllNodes(organization)
          else
            NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier.get)
        
        val nodeGroupQuery: Query[NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.name === highAvailabilityGroup)
                     .filter(_.organization === organization)
        
        val changeRecords: Seq[ResourceChangeRow] =
          Seq(ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                changeId = 0L,
                                id = node,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = organization,
                                public = "false",
                                resource = ResChangeResource.NODE.toString),
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = highAvailabilityGroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.MODIFIED.toString,
                                orgId = organization,
                                public = "false",
                                resource = ResChangeResource.NODEGROUP.toString))
          
        val insertNodeAssignment: DBIOAction[(Option[Int], Int, Option[Long], Int, Option[String]), NoStream, Effect.Read with Effect with Effect.Write] =
          for {
            nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
            
            _ <-
              if (nodeGroupAdmin.isEmpty)
                DBIO.failed(ResourceNotFoundException())
              else if ((!identity.isOrgAdmin && !identity.isSuperUser) &&
                       nodeGroupAdmin.getOrElse(false))
                DBIO.failed(AccessDeniedException())
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
                DBIO.failed(AccessDeniedException())
              else
                DBIO.successful(())
            
            priorAssignment <- Compiled(NodeGroupAssignmentTQ.filter(_.node === resource)
                                                             .map(_.node)
                                                             .distinct).result.headOption
            
            _ <-
              if (priorAssignment.nonEmpty)
                DBIO.failed(AlreadyExistsException())
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
              Compiled(nodeGroupQuery.filterIf(!identity.isOrgAdmin && !identity.isSuperUser)(_.admin === false)
                                     .map(_.lastUpdated))
                                     .update(changeTimestamp.toString)
            
            _ <-
              if (nodeGroupsUpdated.equals(0))
                DBIO.failed(ResourceNotFoundException())
              else
                DBIO.successful(())
            
            nodeGroupID <- Compiled(nodeGroupQuery.filterIf(!identity.isOrgAdmin && !identity.isSuperUser)(_.admin === false)
                                                  .map(_.group)).result.headOption
            
            nodeAssignmentsInserted <-
              NodeGroupAssignmentTQ += NodeGroupAssignmentRow(group = nodeGroupID.get,
                                                              node = resource)
            
            _ <-
              if (!nodeAssignmentsInserted.equals(1))
                DBIO.failed(new IllegalStateException())
              else
                DBIO.successful(())
          } yield (changeRecordsInserted, nodeAssignmentsInserted, nodeGroupID, nodeGroupsUpdated, priorAssignment) // Yielding these for debugging, no functional reason.
        
        implicit val formats: Formats = DefaultFormats
        db.run(insertNodeAssignment.transactionally.asTry).map({
          case Success(result) =>
            Future { logger.debug("DELETE /orgs/" + organization + "/hagroups/" + highAvailabilityGroup + "/nodes/" + node + " updated in changes table: " + result._1.getOrElse(-1)) }
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.node.inserted")))
          case Failure(exception: AccessDeniedException) =>
            Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.ACCESS_DENIED, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.group.access.denied"))))}") }
            (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.group.access.denied")))
          case Failure(exception: AlreadyExistsException) =>
            Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.ALREADY_EXISTS2, Serialization.write(ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.node.conflict", (organization + "/" + node)))))}") }
            (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.node.conflict", (organization + "/" + node))))
          case Failure(exception: IllegalStateException) =>
            Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("changes.not.created", (organization + "/" + highAvailabilityGroup + "/" + node)))))}") }
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("changes.not.created", (organization + "/" + highAvailabilityGroup + "/" + node))))
          case Failure(exception: ResourceNotFoundException) =>
            Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", (organization + "/" + highAvailabilityGroup)))))}") }
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", (organization + "/" + highAvailabilityGroup))))
          case Failure(exception: PSQLException) =>
            if (exception.getServerErrorMessage.getConstraint.equals(NodeGroupAssignmentTQ.baseTableRow.fkNode.fks(0).name)) {
              Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource))))}") }
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource)))
            }
            else {
              Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", resource, exception.toString))))}") }
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", resource, exception.toString)))
            }
          case Failure(exception) =>
            Future { logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup/nodes/$node - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", resource, exception.toString))))}") }
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", resource, exception.toString)))
        })
      })
    }
  
  def nodeHighAvailabilityGroup(identity: Identity2): Route =
    path("orgs" / Segment / "hagroups" / Segment / "nodes" / Segment) {
      (organization,
       highAvailabilityGroup,
       node) =>
        (delete | post) {
          val resource: String = OrgAndId(organization, node).toString
          
          exchAuth(TNode(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteNodeFromNodeGroup(highAvailabilityGroup, identity, node, organization, resource) ~
              postNodeToNodeGroup(highAvailabilityGroup, identity, node, organization, resource)
          }
        }
    }
}
