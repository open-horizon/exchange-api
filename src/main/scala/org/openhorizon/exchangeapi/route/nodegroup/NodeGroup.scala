package org.openhorizon.exchangeapi.route.nodegroup

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.path
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, get, post, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AccessDeniedException, AuthRoles, AuthenticationSupport, BadInputException, Identity, Identity2, OrgAndId, ResourceNotFoundException, TNode}
import org.openhorizon.exchangeapi.table.node.group.{NodeGroupRow, NodeGroupTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.{NodeGroupAssignment, NodeGroupAssignmentRow, NodeGroupAssignmentTQ, PostPutNodeGroupsRequest}
import org.openhorizon.exchangeapi.table.node.{NodeRow, Nodes, NodesTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, HttpCode}
import org.openhorizon.exchangeapi.{ExchangeApiApp, auth}
import org.postgresql.util.PSQLException
import scalacache.modes.scalaFuture.mode
import slick.dbio.Effect
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

@Path("/v1/orgs/{org}/hagroups/{hagroup}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "high availability node group")
trait NodeGroup extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  /* ====== DELETE /orgs/{org}/hagroups/{hagroup} ================================ */
  @DELETE
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
  def deleteNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                      @Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/hagroups/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        val changeTimestamp: Instant = ApiTime.nowUTCTimestamp
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (identity.isOrgAdmin || identity.isSuperUser)
            NodesTQ.getAllNodes(organization)
          else
            NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier)
        val nodeGroupQuery: Query[org.openhorizon.exchangeapi.table.node.group.NodeGroup, NodeGroupRow, Seq] =
          NodeGroupTQ.filter(_.organization === organization)
                     .filter(_.name === highAvailabilityGroup)
        
        val removeNodeGroup: DBIOAction[(Int, Option[Int], Seq[String], Int), NoStream, Effect.Read with Effect with Effect.Write] =
          for {
            nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
            
            _ <-
              if (nodeGroupAdmin.isEmpty)
                DBIO.failed(ResourceNotFoundException())
              else if (!identity.isOrgAdmin &&
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
                                            .length)
                                            .result
            
            _ <-
              if (!assignedNodesNotOwned.equals(0))
                DBIO.failed(AccessDeniedException())
              else
                DBIO.successful(())
            
            nodesToBeRemoved <-
              Compiled(NodeGroupAssignmentTQ.join(nodeGroupQuery.map(_.group)).on(_.group === _).map(_._1.node)).result
            
            numNodeGroupsRemoved <- Compiled(nodeGroupQuery).delete
            
            _ <-
              if (numNodeGroupsRemoved.equals(0))
                DBIO.failed(ResourceNotFoundException())
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
                                    orgId = organization,
                                    public = "false",
                                    resource = ResChangeResource.NODE.toString)) :+
              ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                changeId = 0L,
                                id = highAvailabilityGroup,
                                lastUpdated = changeTimestamp,
                                operation = ResChangeOperation.DELETED.toString,
                                orgId = organization,
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
              logger.debug("DELETE /orgs/" + organization + "/hagroups/" + highAvailabilityGroup + " updated in changes table: " + result)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.deleted")))
          case Failure(t: AccessDeniedException) =>
            (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, t.getMessage))
          case Failure(t: ResourceNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, t.getMessage))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{org}/hagroups/{hagroup} ================================ */
  @GET
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
  def getNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                   @Parameter(hidden = true) identity: Identity2,
                   @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"GET /orgs/$organization/hagroups - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        val nodesQuery: Query[Nodes, NodeRow, Seq] =
          if (identity.isOrgAdmin ||
              identity.isSuperUser ||
              identity.isAgbot)
            NodesTQ.getAllNodes(organization)
          else
            NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier.getOrElse(identity.owner.get))
        
        val getNodeGroup =
          for {
            nodeGroupWAssignments <-
              NodeGroupTQ.getAllNodeGroups(organization)
                         .filter(_.name === highAvailabilityGroup)
                         .joinLeft(NodeGroupAssignmentTQ.filter(_.node in nodesQuery.map(_.id)))
                         .on(_.group === _.group)
          } yield (nodeGroupWAssignments)
        
        db.run(Compiled(getNodeGroup.sortBy(_._2.map(_.node).getOrElse(""))).result.transactionally.asTry).map({
          case Success(result) =>
            logger.debug(s"${result.toString()}")
            if (result.nonEmpty)
              if (result.head._2.isDefined)
                (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(admin = result.head._1.admin,
                                                                      description =
                                                                        if ((!identity.isOrgAdmin && !identity.isSuperUser) && result.head._1.admin)
                                                                          ""
                                                                        else
                                                                          result.head._1.description.getOrElse(""),
                                                                      lastUpdated = result.head._1.lastUpdated,
                                                                      members = result.map(_._2.get.node.split("/")(1)),
                                                                      name = result.head._1.name))))
              else
                (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(admin = result.head._1.admin,
                                                                      description =
                                                                        if ((!identity.isOrgAdmin && !identity.isSuperUser) && result.head._1.admin)
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
  
  /* ====== PUT /orgs/{org}/hagroups/{hagroup} ================================ */
  @PUT
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
  def putNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                   @Parameter(hidden = true) identity: Identity2,
                   @Parameter(hidden = true) organization: String): Route =
    {
      entity(as[PutNodeGroupsRequest]) {
        reqBody =>
          Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
            validateWithMsg(reqBody.getAnyProblem) {
              complete({
                val changeTimestamp: Instant = ApiTime.nowUTCTimestamp
                val members: Option[Seq[String]] =
                  if (reqBody.members.isEmpty)
                    None
                  else if (reqBody.members.get.isEmpty)
                    Option(Seq())
                  else
                    Option(reqBody.members.get.distinct.map(node => OrgAndId(organization, node).toString)) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
                
                val nodeGroupQuery: Query[org.openhorizon.exchangeapi.table.node.group.NodeGroup, NodeGroupRow, Seq] =
                  NodeGroupTQ.filter(_.organization === organization)
                             .filter(_.name === highAvailabilityGroup)
                val nodesQuery: Query[Nodes, NodeRow, Seq] =
                  if (identity.isOrgAdmin ||
                      identity.isSuperUser)
                    NodesTQ.getAllNodes(organization)
                  else
                    NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier.get)
                
                val syncNodeGroup: DBIOAction[(Seq[String], Option[Int], Seq[String], Seq[String]), NoStream, Effect.Write with Effect with Effect.Read] =
                  for {
                    _ <-
                      if (identity.isHubAdmin)
                        DBIO.failed(AccessDeniedException())
                      else
                        DBIO.successful(())
                    
                    nodeGroupAdmin <- Compiled(nodeGroupQuery.map(_.admin)).result.headOption
                    
                    _ <-
                      if (nodeGroupAdmin.isEmpty)
                        DBIO.failed(ResourceNotFoundException())
                      else if (!identity.isOrgAdmin &&
                               nodeGroupAdmin.getOrElse(false))
                        DBIO.failed(AccessDeniedException())
                      else
                        DBIO.successful(())
                  
                    numNodeGroupsUpdated <-
                      if (reqBody.description.nonEmpty)
                        Compiled(nodeGroupQuery.map(nodeGroup => (nodeGroup.description, nodeGroup.lastUpdated)))
                                      .update((reqBody.description, changeTimestamp.toString))
                      else
                        Compiled(nodeGroupQuery.map(_.lastUpdated))
                                      .update(changeTimestamp.toString)
        
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
                        DBIO.failed(AccessDeniedException())
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
                        DBIO.failed(AccessDeniedException())
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
                                          orgId = organization,
                                          public = "false",
                                          resource = ResChangeResource.NODE.toString)) :+
                        ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                          changeId = 0L,
                                          id = highAvailabilityGroup,
                                          lastUpdated = changeTimestamp,
                                          operation = ResChangeOperation.MODIFIED.toString,
                                          orgId = organization,
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
                
                implicit val formats: Formats = DefaultFormats
                db.run(syncNodeGroup.transactionally.asTry).map({
                  case Success(result) =>
                    logger.debug(s"PUT /orgs/$organization/hagroups/$highAvailabilityGroup update successful")
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.updated.successfully", highAvailabilityGroup, organization)))
                  case Failure(exception: ResourceNotFoundException) =>
                    Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, exception.getMessage)))}") }
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, exception.getMessage))
                  case Failure(exception: BadInputException) =>
                    Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.BAD_INPUT,  Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, exception.getMessage)))}") }
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, exception.getMessage))
                  case Failure(exception: auth.AlreadyExistsException) =>
                    Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.ALREADY_EXISTS2,  Serialization.write(ApiResponse(ApiRespType.ALREADY_EXISTS, exception.getMessage)))}") }
                    (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, exception.getMessage))
                  case Failure(exception: AccessDeniedException) =>
                    Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.ACCESS_DENIED,  Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, exception.getMessage)))}") }
                    (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, exception.getMessage))
                  case Failure(exception: PSQLException) =>
                    if (exception.getServerErrorMessage.getConstraint.equals(NodeGroupTQ.baseTableRow.nodeGroupIdx.name)) {
                      Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.ALREADY_EXISTS2, Serialization.write(ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", (organization + "/" + highAvailabilityGroup)))))}") }
                      (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", (organization + "/" + highAvailabilityGroup))))
                    }
                    else if (exception.getServerErrorMessage.getConstraint.equals(NodeGroupTQ.baseTableRow.fkOrg.fks(0).name)) {
                      Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization))))}") }
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization)))
                    }
                    else if (exception.getServerErrorMessage.getConstraint.equals(NodeGroupAssignmentTQ.baseTableRow.pkNodeGroup.name)) {
                      Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.ALREADY_EXISTS2, Serialization.write(ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict"))))}") }
                      (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict")))
                    }
                    else {
                      Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", (organization + "/" + highAvailabilityGroup), exception.toString))))}") }
                      (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", (organization + "/" + highAvailabilityGroup), exception.toString)))
                    }
                  case Failure(exception) =>
                    Future { logger.debug(s"PUT /orgs/$organization/users/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString}  - ${(HttpCode.INTERNAL_ERROR,  Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", highAvailabilityGroup, organization, exception.getMessage))))}") }
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", highAvailabilityGroup, organization, exception.getMessage)))
                })
              })
            }
      }
    }
  
  /* ====== POST /orgs/{org}/hagroups/{hagroup} ================================ */
  @POST
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
  def postNodeGroup(@Parameter(hidden = true) highAvailabilityGroup: String,
                    @Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route =
    post {
      entity(as[PostPutNodeGroupsRequest]) {
        reqBody =>
          logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val admin: Boolean = (identity.isOrgAdmin || identity.isSuperUser)
              val changeTimestamp: Instant = ApiTime.nowUTCTimestamp
              val nodeGroupQuery: Query[org.openhorizon.exchangeapi.table.node.group.NodeGroup, NodeGroupRow, Seq] =
                NodeGroupTQ.filter(_.organization === organization)
                           .filter(_.name === highAvailabilityGroup)
              val nodesQuery: Query[Nodes, NodeRow, Seq] =
                if (admin)
                  NodesTQ.getAllNodes(organization)
                else
                  NodesTQ.getAllNodes(organization).filter(_.owner === identity.identifier.get)
              val members: Seq[String] =
                if (reqBody.members.isEmpty ||
                    reqBody.members.get.isEmpty)
                  Seq()
                else
                  reqBody.members.get.distinct.map(node => OrgAndId(id = node, org = organization).toString) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
                  
              logger.debug(s"${members.toString()}")
              
              val createNodeGroup: DBIOAction[Unit, NoStream, Effect.Write with Effect.Read with Effect] =
                for {
                  // Automatically return the assigned node group identifier when creating a new Node Group.
                  group <-
                    (NodeGroupTQ returning NodeGroupTQ.map(_.group)) +=
                      NodeGroupRow(admin = admin,
                                   description = reqBody.description,
                                   group = 0L,
                                   organization = organization,
                                   lastUpdated = changeTimestamp.toString,
                                   name = highAvailabilityGroup)
                  
                  /*
                   * Grab Nodes that exist based off what was provided in the request body filtering on ownership if
                   * applicable. Convert records into something That can be stored in NodeGroupAssignment.
                   */
                  nodesToAssign <-
                    Compiled(nodesQuery.filter(_.id inSet members)
                                       .map(_.id))
                                       .result
                                       .map(nodes => nodes.map(node =>
                                                                 NodeGroupAssignmentRow(group = group,
                                                                                        node = node)))
                  
                  // Authorization check for all nodes in the request body.
                  _ <-
                    if (!(nodesToAssign.sizeIs == members.length))
                      DBIO.failed(AccessDeniedException())
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
                                        orgId = organization,
                                        public = "false",
                                        resource = ResChangeResource.NODE.toString)) :+
                    ResourceChangeRow(category = ResChangeCategory.NODEGROUP.toString,
                                      changeId = 0L,
                                      id = highAvailabilityGroup,
                                      lastUpdated = changeTimestamp,
                                      operation = ResChangeOperation.CREATED.toString,
                                      orgId = organization,
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
                  logger.debug(s"POST /orgs/$organization/hagroups/$highAvailabilityGroup result: $result")
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.created", resource)))
                case Failure(e: AccessDeniedException) =>
                  (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("access.denied.no.auth", identity.resource, "WRITE_ALL_NODES", "")))
                case Failure(e: IllegalStateException) =>
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.records.not.inserted")))
                case Failure(e: PSQLException) =>
                  if (e.getServerErrorMessage.getConstraint.equals(NodeGroupTQ.baseTableRow.nodeGroupIdx.name))
                    (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", (organization + "/" + highAvailabilityGroup))))
                  else if (e.getServerErrorMessage.getConstraint.equals(NodeGroupTQ.baseTableRow.fkOrg.fks(0).name))
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization)))
                  else if (e.getServerErrorMessage.getConstraint.equals(NodeGroupAssignmentTQ.baseTableRow.pkNodeGroup.name))
                    (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict")))
                  else
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.node.not.inserted", resource, e.toString)))
                case Failure(t) =>
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
              })
            })
          }
      }
    }
  
  def nodeGroup(identity: Identity2): Route =
    path("orgs" / Segment / "hagroups" / Segment) {
      (organization,
       highAvailabilityGroup) =>
        val resource: String = OrgAndId(organization, highAvailabilityGroup).toString
        
        /*val i: Option[UUID] =
          try
            Option(Await.result(cacheResourceOwnership.cachingF(organization, highAvailabilityGroup)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
              ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, something = "node")
            }, 15.seconds))
          catch {
            case _: Throwable => None
          }*/
        
        (delete | post) {
          exchAuth(TNode(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteNodeGroup(highAvailabilityGroup, identity, organization, resource) ~
              postNodeGroup(highAvailabilityGroup, identity, organization, resource)
          }
        } ~
        get {
          exchAuth(TNode(OrgAndId(organization, "#").toString), Access.READ, validIdentity = identity) {
            _ =>
              getNodeGroup(highAvailabilityGroup, identity, organization)
          }
        } ~
        put {
          exchAuth(TNode(OrgAndId(organization, "#").toString), Access.WRITE, validIdentity = identity) {
            _ =>
              putNodeGroup(highAvailabilityGroup, identity, organization)
          }
        }
    }
}
