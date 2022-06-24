package com.horizon.exchangeapi

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import slick.jdbc.PostgresProfile.api._

import javax.ws.rs.{DELETE, Path}
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.DBProcessingError
import com.horizon.exchangeapi.tables.{NodeGroupTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn

import scala.util.{Failure, Success}

/** Implementation for all of the /orgs/{org}/AgentFileVersion routes */
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
//
//  /* ====== GET /orgs/{orgid}/hagroups ================================ */
//  def getAllNodeGroup: Route = (path("orgs" / Segment / "hagroups" / Segment) & get) { (orgid, name) =>
//
//  }
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