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
import com.horizon.exchangeapi.tables.{NodeGroupAssignmentTQ, NodeGroupTQ, NodesTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
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
    deleteNodeGroup
//      getNodeGroup ~
//      getAllNodeGroup ~
//      putNodeGroup ~
//      postNodeGroup

  /* ====== DELETE /orgs/{orgid}/hagroup/{name} ================================ */
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
  def deleteNodeGroup: Route = (path("orgs" / Segment / "hagroup" / Segment) & delete) { (orgid, name) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/hagroup/$name")
    val compositeId: String = OrgAndId(orgid, name).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeGroupTQ.getNodeGroupName(name).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/hagroup/" + name + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, name, ResChangeCategory.NODEGROUP, false, ResChangeResource.NODEGROUP, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.group.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/hagroup/" + name + " updated in changes table: " + v)
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

//  /* ====== GET /orgs/{orgid}/hagroup/{name} ================================ */
//  def getNodeGroup: Route = (path("orgs" / Segment / "hagroup" / Segment) & get) { (orgid, name) =>
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
        val q1 = NodeGroupTQ.getAllNodeGroups(orgid)
        val q2 = if (ident.isAdmin) NodesTQ.getAllNodes(orgid) else NodesTQ.filter(_.owner === ident.identityString)
        val q3 = for {
          ((nodeGroup, nodeGroupAssignment), _) <- (q1 joinLeft NodeGroupAssignmentTQ on (_.group === _.group)) join q2 on (_._2.map(_.node) === _.id)
        } yield (nodeGroupAssignment.map(_.node), nodeGroup.group, nodeGroup.name, nodeGroup.updated)
        db.run(q3.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/hagroups result size: ${list.size}")
          if (list.isEmpty) (HttpCode.NOT_FOUND, GetNodeGroupsResponse(Seq.empty[NodeGroupResp]))
          else {
            var resp: ListBuffer[NodeGroupResp] = ListBuffer[NodeGroupResp]()
            var members: ListBuffer[String] = ListBuffer[String]()
            var lastGroup: String = list.head._2
            var lastName: String = list.head._3
            var lastUpdated: String = list.head._4
            for ((nodeId, group, groupName, updated) <- list) {
              if (group != lastGroup) {
                resp += NodeGroupResp(lastName, members.toSeq, lastUpdated)
                lastGroup = group
                lastName = groupName
                lastUpdated = updated
                members = ListBuffer[String]()
              }
              if (nodeId.isDefined) members += nodeId.get
            }
            resp += NodeGroupResp(lastName, members.toSeq, lastUpdated)
            (HttpCode.OK, GetNodeGroupsResponse(resp.toSeq))
          }
        })
      })
    }
  }
//
//  /* ====== PUT /orgs/{orgid}/hagroup/{name} ================================ */
//  def putNodeGroup: Route = (path("orgs" / Segment / "hagroup" / Segment) & put) { (orgid, name) =>
//
//  }
//
//  /* ====== POST /orgs/{orgid}/hagroup/{name} ================================ */
//  def postNodeGroup: Route = (path("orgs" / Segment / "hagroup" / Segment) & post) { (orgid, name) =>
//
//  }

}