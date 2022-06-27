package com.horizon.exchangeapi

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import slick.jdbc.PostgresProfile.api._

import javax.ws.rs.{DELETE, GET, Path}
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.DBProcessingError
import com.horizon.exchangeapi.tables.{NodeGroupAssignmentRow, NodeGroupAssignmentTQ, NodeGroupRow, NodeGroupTQ, NodeRow, Nodes, NodesTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}

/** Output format for GET /hagroups */
final case class NodeGroupResp(name: String, members: Seq[String], updated: String)
final case class GetNodeGroupsResponse(nodeGroups: Seq[NodeGroupResp])

/** Implementation for all of the /orgs/{orgid}/hagroups routes */
@Path("/v1/orgs/{orgid}/hagroups")
@io.swagger.v3.oas.annotations.tags.Tag(name = "nodegroup")
trait NodeGroupRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def nodeGroupRoutes: Route =
    deleteNodeGroup ~
//      getNodeGroup ~
    getAllNodeGroup
//      putNodeGroup ~
//      postNodeGroup

  /* ====== DELETE /orgs/{orgid}/hagroups/{name} ================================ */
  @DELETE
  @Path("{name}")
  @Operation(
    summary = "Deletes a Node Group",
    description = "Deletes an Highly Available Node Group by name",
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
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeGroupTQ.getNodeGroupName(name).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/hagroups/" + name + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, name, ResChangeCategory.NODEGROUP, false, ResChangeResource.NODEGROUP, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/hagroups/" + name + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.group.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.group.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.group.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

//  /* ====== GET /orgs/{orgid}/hagroups/{name} ================================ */
//  def getNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & get) { (orgid, name) =>
//
//  }

  /* ====== GET /orgs/{orgid}/hagroups ================================ */
  @GET
  @Path("")
  @Operation(
    summary = "Lists all members of all Node Groups (HA Groups)",
    description = "Returns 0 or more Node Groups and all node IDs associated with each group that the caller has permission to view.",
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
      "updated": "2020-02-05T20:28:14.469Z[UTC]"
    },
    {
      "name": "nodegroup2",
      "members": [
        "nodeA",
        "nodeB",
        "nodeC"
      ],
      "updated": "2020-02-05T20:28:14.469Z[UTC]"
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
        val nodesQuery = if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) NodesTQ.getAllNodes(orgid) else NodesTQ.filter(_.owner === ident.identityString)
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
                if (assignmentMap.contains(nodeGroup.group)) response += NodeGroupResp(nodeGroup.name, assignmentMap(nodeGroup.group).map(_.node).collect{_.split("/")(1)}, nodeGroup.updated)
                else response += NodeGroupResp(nodeGroup.name, Seq.empty[String], nodeGroup.updated) //node group with no assignments
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
//
//  /* ====== PUT /orgs/{orgid}/hagroups/{name} ================================ */
//  def putNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & put) { (orgid, name) =>
//
//  }
//
//  /* ====== POST /orgs/{orgid}/hagroups/{name} ================================ */
//  def postNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & post) { (orgid, name) =>
//
//  }

}