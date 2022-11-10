package com.horizon.exchangeapi

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import slick.jdbc.PostgresProfile.api._

import jakarta.ws.rs.{DELETE, GET, PUT, POST, Path}
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.headers.CacheDirectives.public
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.{AccessDeniedException, AlreadyExistsException, BadInputException, DBProcessingError, ResourceNotFoundException}
import com.horizon.exchangeapi.tables.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupAssignments, NodeGroupRow, NodeGroupTQ, Nodes, NodesTQ, PostPutNodeGroupsRequest, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import org.json4s.{DefaultFormats, Formats}
import slick.lifted.Rep

import java.sql.Timestamp
import scala.collection.immutable.{List, Set}
import scala.collection.mutable.ListBuffer
import scala.util.matching.Regex
import scala.util.{Failure, Success}

/** Output format for GET /hagroups */
final case class NodeGroupResp(name: String, description: String, members: Seq[String], lastUpdated: String)
final case class GetNodeGroupsResponse(nodeGroups: Seq[NodeGroupResp])

/** Input format for POST/PUT /orgs/{orgid}/hagroups/<name> */
final case class PutNodeGroupsRequest(members: Option[Seq[String]], description: Option[String]) {
  def getAnyProblem: Option[String] = None
}

/** Implementation for all of the /orgs/{orgid}/hagroups routes */
@Path("/v1/orgs/{orgid}/hagroups")
@io.swagger.v3.oas.annotations.tags.Tag(name = "hagroup")
trait NodeGroupRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def nodeGroupRoutes: Route =
    deleteNodeGroup ~
    getNodeGroup ~
    getAllNodeGroup ~
    putNodeGroup ~
    postNodeGroup


  /* ====== DELETE /orgs/{orgid}/hagroups/{name} ================================ */
  @DELETE
  @Path("{name}")
  @Operation(
    summary = "Deletes a Node Group",
    description = "Deletes a Highly Available Node Group by name",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization identifier."),
      new Parameter(
        name = "name",
        in = ParameterIn.PATH,
        description = "Node identifier")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & delete) { (orgid, name) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/hagroups/$name")
    val compositeId: String = OrgAndId(orgid, name).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      complete({
        val nodesQuery = if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) NodesTQ.getAllNodes(orgid) else NodesTQ.getAllNodes(orgid).filter(_.owner === ident.identityString)
        val nodeGroupQuery = NodeGroupTQ.filter(_.organization === orgid).filter(_.name === name)
        //I need to get all the nodes inside of a group... If all nodes from "query" is owned by the caller, then we can delete
        val deleteQuery = for {
          nodeGroup <- nodeGroupQuery.result // will be empty if wrong group is provided
          nodesNotOwned <- NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group)).filterNot(_.node in nodesQuery.map(_.id)).result //empty if caller owns all old nodes
          removedNodes <- NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group)).map(_.node).result

          _ <- {
            if (nodesNotOwned.isEmpty &&
                nodeGroup.nonEmpty) {
              val action = NodeGroupTQ.getNodeGroupName(orgid, name).delete
              DBIO.seq(
                action,
                ResourceChange(changeId = 0L,
                               orgId = orgid,
                               id = name,
                               category = ResChangeCategory.NODEGROUP,
                               public = false,
                               resource = ResChangeResource.NODEGROUP,
                               operation = ResChangeOperation.DELETED).insert,
                ResourceChangesTQ ++=
                removedNodes.map(node =>
                    ResourceChange(changeId = 0L,
                                   orgId = orgid,
                                   id = ("[^/]+$".r findFirstIn node).getOrElse(""),
                                   category = ResChangeCategory.NODE,
                                   public = false,
                                   resource = ResChangeResource.NODE,
                                   operation = ResChangeOperation.MODIFIED).toResourceChangeRow)
              )
            }
            else DBIO.successful(())
          }
        } yield (nodeGroup, nodesNotOwned)

        db.run(deleteQuery.transactionally.asTry).map({
          case Success(result) =>
            if (result._1.isEmpty) {
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", compositeId)))
            }
            else if (result._2.nonEmpty) {
              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.group.access.denied", compositeId))) //todo: translate w/ error message
            }
            else {
              logger.debug("DELETE /orgs/" + orgid + "/hagroups/" + name + " updated in changes table: " + result)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.deleted")))
            }
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/hagroups/{name} ================================ */
  @GET
  @Path("{name}")
  @Operation(
    summary = "Lists all members of the specified Node Group (HA Group)",
    description = "Returns the Node Group along with the member nodes that the caller has permission to view.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization identifier."
      ),
      new Parameter(
        name = "name",
        in = ParameterIn.PATH,
        description = "Node Group identifier."
      )
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
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
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodeGroupsResponse])
          )
        )
      ),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")
    )
  )
  def getNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & get) { (orgid, name) =>
    logger.debug(s"doing GET /orgs/$orgid/hagroups")
    exchAuth(TNode(OrgAndId(orgid, "#").toString), Access.READ) { ident =>
      complete({
        val nodesQuery = if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) NodesTQ.getAllNodes(orgid) else NodesTQ.getAllNodes(orgid).filter(_.owner === ident.identityString)
        val query = NodeGroupTQ.getAllNodeGroups(orgid).filter(_.name === name) joinLeft NodeGroupAssignmentTQ.filter(_.node in nodesQuery.map(_.id)) on (_.group === _.group)
        db.run(query.sortBy(_._2.map(_.node).getOrElse("")).result.transactionally.asTry).map({
          case Success(result) =>
            if (result.nonEmpty) {
              if (result.head._2.isDefined) (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(result.head._1.name, result.head._1.description, result.map(_._2.get.node.split("/")(1)), result.head._1.lastUpdated))))
              else (HttpCode.OK, GetNodeGroupsResponse(Seq(NodeGroupResp(result.head._1.name, result.head._1.description, Seq.empty[String], result.head._1.lastUpdated)))) //node group with no members
            }
            else (HttpCode.NOT_FOUND, GetNodeGroupsResponse(ListBuffer[NodeGroupResp]().toSeq)) //node group doesn't exist
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("api.internal.error")))
        })
      })
    }
  }

  /* ====== GET /orgs/{orgid}/hagroups ================================ */
  @GET
  @Path("")
  @Operation(
    summary = "Lists all members of all Node Groups (HA Groups)",
    description = "Returns all Node Groups in this org along with the member nodes that the caller has permission to view.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization identifier."
      )
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
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
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodeGroupsResponse])
          )
        )
      ),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")
    )
  )
  def getAllNodeGroup: Route = (path("orgs" / Segment / "hagroups") & get) { (orgid) =>
    logger.debug(s"doing GET /orgs/$orgid/hagroups")
    exchAuth(TNode(OrgAndId(orgid, "#").toString), Access.READ) { ident =>
      complete({
        val nodeGroupsQuery = NodeGroupTQ.getAllNodeGroups(orgid).sortBy(_.name)
        val nodesQuery = if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) NodesTQ.getAllNodes(orgid) else NodesTQ.getAllNodes(orgid).filter(_.owner === ident.identityString)
        val queries: DBIOAction[(Seq[NodeGroupRow], Seq[NodeGroupAssignmentRow]), NoStream, Effect.Read] =
          for {
            nodeGroups <- nodeGroupsQuery.result
            nodeGroupAssignments <- NodeGroupAssignmentTQ.filter(_.node in nodesQuery.map(_.id)).filter(_.group in nodeGroupsQuery.map(_.group)).sortBy(a => (a.group, a.node)).result
          } yield (nodeGroups, nodeGroupAssignments)
        db.run(queries.transactionally.asTry).map({
          case Success(result) =>
            if (result._1.nonEmpty) {
              val assignmentMap = result._2.groupBy(_.group)
              val response: ListBuffer[NodeGroupResp] = ListBuffer[NodeGroupResp]()
              for (nodeGroup <- result._1) {
                if (assignmentMap.contains(nodeGroup.group)) response += NodeGroupResp(nodeGroup.name, nodeGroup.description, assignmentMap(nodeGroup.group).map(_.node.split("/")(1)), nodeGroup.lastUpdated)
                else response += NodeGroupResp(nodeGroup.name, nodeGroup.description, Seq.empty[String], nodeGroup.lastUpdated) //node group with no assignments
              }
              (HttpCode.OK, GetNodeGroupsResponse(response.toSeq))
            }
            else (HttpCode.NOT_FOUND, GetNodeGroupsResponse(ListBuffer[NodeGroupResp]().toSeq)) //no node groups in org
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("api.internal.error")))
        })
      })
    }
  }

  /* ====== PUT /orgs/{orgid}/hagroups/{name} ================================ */
  @PUT
  @Path("{name}")
  @Operation(
    summary = "Update the nodes that belong to an existing Node Group (HA Group)",
    description = "Replaces the list of nodes that belong to the specified node group with the list of nodes provided in the request body.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization identifier."
      ),
      new Parameter(
        name = "name",
        in = ParameterIn.PATH,
        description = "Node Group identifier."
      )
    ),
    requestBody = new RequestBody(
      required = true,
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
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeGroupsRequest])
        )
      )
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found"),
      new responses.ApiResponse(responseCode = "409", description = "node already belongs to other group")
    )
  )
  def putNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & put & entity(as[PutNodeGroupsRequest])) { (orgid, name, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgid/users/$name")
    exchAuth(TNode(OrgAndId(orgid, "#").toString), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val members: Seq[String] =
            if (reqBody.members.isDefined)
              reqBody.members.get.distinct.map(a => orgid + "/" + a) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
            else Seq.empty[String]

          val nodeGroupQuery = NodeGroupTQ.filter(_.organization === orgid).filter(_.name === name)
          val nodesQuery = if (ident.isAdmin) NodesTQ.getAllNodes(orgid) else NodesTQ.getAllNodes(orgid).filter(_.owner === ident.identityString)

          val queries = for {
            _ <- {
              if (reqBody.members.isEmpty && reqBody.description.isEmpty) DBIO.failed(new BadInputException(ExchMsg.translate("no.valid.node.group.attribute.specified")))
              else DBIO.successful(())
            }

            nodeGroup <- nodeGroupQuery.result
            _ <- {
              if (nodeGroup.isEmpty) DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.group.not.found", orgid + "/" + name)))
              else DBIO.successful(())
            }

            nodesCallerDoesntOwn <- NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group)).filterNot(_.node in nodesQuery.map(_.id)).result //empty if caller owns all old nodes
            _ <- {
              if (nodesCallerDoesntOwn.nonEmpty) DBIO.failed(new AccessDeniedException(ExchMsg.translate("node.group.access.denied")))
              else DBIO.successful(())
            }

            _ <- {
              NodeGroupRow(
                if (reqBody.description.isDefined) reqBody.description.get else nodeGroup.head.description,
                nodeGroup.head.group,
                nodeGroup.head.organization,
                ApiTime.nowUTC,
                nodeGroup.head.name
              ).update
            } //this will get rolled back if we hit a failure later on

            //todo: is there a way to stop these next 2 queries from running if 'members' is not provided?
            nodesInOtherGroups <- NodeGroupAssignmentTQ.filterNot(_.group in nodeGroupQuery.map(_.group)).filter(_.node inSet members).result //empty if no nodes in list are in other groups
            _ <- {
              if (reqBody.members.isDefined && nodesInOtherGroups.nonEmpty) DBIO.failed(new AlreadyExistsException(ExchMsg.translate("node.group.conflict")))
              else DBIO.successful(())
            }
            
            ownedNodesInList <- nodesQuery.filter(_.id inSet members).result //if caller owns all new nodes, length should equal length of 'members'
            _ <- {
              if (reqBody.members.isDefined && (ownedNodesInList.length != members.length)) DBIO.failed(new AccessDeniedException(ExchMsg.translate("node.group.node.access.denied")))
              else DBIO.successful(())
            }
            
            oldNodes <- NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group)).map(_.node).result
            nodesChanged = members.filterNot(oldNodes.contains(_)) ++ oldNodes.filterNot(members.contains(_))

            _ <- {
              if (reqBody.members.isDefined) {
                DBIO.seq(
                  NodeGroupAssignmentTQ.filter(_.group in nodeGroupQuery.map(_.group)).delete,
                  NodeGroupAssignmentTQ ++= members.map(a => NodeGroupAssignmentRow(a, nodeGroup.head.group)),
                  ResourceChangesTQ ++=
                    nodesChanged.map(node =>
                      ResourceChange(changeId = 0L,
                                     orgId = orgid,
                                     id = ("[^/]+$".r findFirstIn node).getOrElse(""),
                                     category = ResChangeCategory.NODE,
                                     public = false,
                                     resource = ResChangeResource.NODE,
                                     operation = ResChangeOperation.MODIFIED
                  ).toResourceChangeRow)
                )
              }
              else DBIO.successful(())
            }
            
            _ <- ResourceChange(changeId = 0L,
                                orgId = orgid,
                                id = name,
                                category = ResChangeCategory.NODEGROUP,
                                public = false,
                                resource = ResChangeResource.NODEGROUP,
                                operation = ResChangeOperation.MODIFIED).insert
          } yield ()

          db.run(queries.transactionally.asTry).map({
            case Success(result) =>
              logger.debug(s"PUT /orgs/$orgid/hagroups/$name update successful")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.updated.successfully", name, orgid)))
            case Failure(t: ResourceNotFoundException) =>
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, t.getMessage))
            case Failure(t: BadInputException) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, t.getMessage))
            case Failure(t: AlreadyExistsException) =>
              (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, t.getMessage))
            case Failure(t: AccessDeniedException) =>
              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, t.getMessage))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.updated", name, orgid, t.getMessage)))
          })
        })
      }
    }
  }
//

  /* ====== POST /orgs/{orgid}/hagroups/{name} ================================ */
  @POST
  @Path("{name}")
  @Operation(
    summary = "Insert the nodes that belong to an existing Node Group (HA Group)",
    description = "Creates the list of nodes that belong to the specified node group with the list of nodes provided in the request body.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization identifier."
      ),
      new Parameter(
        name = "name",
        in = ParameterIn.PATH,
        description = "Node Group identifier."
      )
    ),
    requestBody = new RequestBody(
      required = true,
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
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeGroupsRequest])
        )
      )
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied: node isn't owned by caller"),
      new responses.ApiResponse(responseCode = "404", description = "not found"),
      new responses.ApiResponse(responseCode = "409", description = "node already belongs to other group")
    )
  )
  def postNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & post & entity(as[PostPutNodeGroupsRequest])) { (orgid, name, reqBody) =>
    val compositeId: String = OrgAndId(orgid, name).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val nodeGroupQuery = NodeGroupTQ.filter(_.organization === orgid).filter(_.name === name)
          val nodesQuery = if (ident.isAdmin) NodesTQ.getAllNodes(orgid) else NodesTQ.getAllNodes(orgid).filter(_.owner === ident.identityString)
          val members: Seq[String] =
            if (reqBody.members.nonEmpty)
              reqBody.members.distinct.map(a => orgid + "/" + a) //don't want duplicate nodes in list, and need to pre-pend orgId to match id in Node table
            else Seq.empty[String]

          val queries = for {
            nodeGroup <- nodeGroupQuery.result //should be empty if POST

            nodesInOtherGroups <- NodeGroupAssignmentTQ.filterNot(_.group in nodeGroupQuery.map(_.group)).filter(_.node inSet members).result //empty if no nodes in list are in other groups

            ownedNodesInList <- nodesQuery.filter(_.id inSet members).result //if caller owns all new nodes, length should equal length of 'members'

            skipGroupAssignment: Boolean =
              if (nodeGroup.nonEmpty)
                true
              else
                false

            _ <- {
              if (nodeGroup.isEmpty && nodesInOtherGroups.isEmpty && (ownedNodesInList.length == members.size)) {
                val action = reqBody.getDbUpsertGroup(orgid, name, reqBody.description)
                DBIO.seq(action,
                         ResourceChange(changeId = 0L,
                                        orgId = orgid,
                                        id = name,
                                        category = ResChangeCategory.NODEGROUP,
                                        public = false,
                                        resource = ResChangeResource.NODEGROUP,
                                        operation = ResChangeOperation.CREATED).insert)
              }
              else DBIO.successful(())
            }

            nodeGroupId <- nodeGroupQuery.result

            _ <- {
              if (skipGroupAssignment == false &&
                  nodeGroupId.nonEmpty) {
                DBIO.seq(
                  NodeGroupAssignmentTQ ++= members.map(a => NodeGroupAssignmentRow(a, nodeGroupId.head.group)),
                  ResourceChangesTQ ++=
                    members.map(node =>
                      ResourceChange(changeId = 0L,
                                     orgId = orgid,
                                     id = ("[^/]+$".r findFirstIn node).getOrElse(""),
                                     category = ResChangeCategory.NODE,
                                     public = false,
                                     resource = ResChangeResource.NODE,
                                     operation = ResChangeOperation.MODIFIED).toResourceChangeRow)
                )
              }
              else DBIO.successful(())
            }
          } yield (nodeGroup, nodesInOtherGroups, ownedNodesInList, nodeGroupId)

          db.run(queries.transactionally.asTry).map({
            case Success(result) =>
              if (result._1.nonEmpty) {
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.already.exists", compositeId))) //todo: translate w/ error message
              }
              else if (result._2.nonEmpty) {
                (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("node.group.conflict", compositeId))) //todo: translate w/ error message
              }
              else if (result._3.length != members.size) {
                (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED,ExchMsg.translate("node.group.node.access.denied", compositeId))) //todo: translate w/ error message
              }
              else {
                logger.debug(s"POST /orgs/$orgid/hagroups/$name result: $result")
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.created", compositeId))) //todo: translate
              }
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }
//


}