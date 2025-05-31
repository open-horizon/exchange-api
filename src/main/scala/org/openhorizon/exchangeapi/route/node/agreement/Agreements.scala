package org.openhorizon.exchangeapi.route.node.agreement

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.GetNodeAgreementsResponse
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreement, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/nodes/{node}/agreements")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
trait Agreements extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/agreements ===============================
  @DELETE
  @Operation(summary = "Deletes all agreements of a node", description = "Deletes all of the current agreements of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteAgreementsNode(@Parameter(hidden = true) identity: Identity2,
                           @Parameter(hidden = true) node: String,
                           @Parameter(hidden = true) organization: String,
                           @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/agreements - By ${identity.resource}:${identity.role}")
    complete({
      // remove does *not* throw an exception if the key does not exist
      db.run(NodeAgreementsTQ.getAgreements(resource).delete.asTry.flatMap({
        case Success(v) =>
          if (v > 0) { // there were no db errors, but determine if it actually found it or not
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /nodes/" + node + "/agreements result: " + v)
            ResourceChange(0L, organization, node, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
          } else {
            DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.node.agreements.found", resource))).asTry
          }
        case Failure(t) => DBIO.failed(t).asTry
      }).flatMap({
        case Success(v) =>
          logger.debug("DELETE /nodes/" + node + "/agreements updated in changes table: " + v)
          NodesTQ.setLastUpdated(resource, ApiTime.nowUTC).asTry
        case Failure(t) => DBIO.failed(t).asTry
      })).map({
        case Success(v) =>
          logger.debug("DELETE /nodes/" + node + "/agreements lastUpdated field updated: " + v)
          (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreements.deleted")))
        case Failure(t: DBProcessingError) =>
          t.toComplete
        case Failure(t: org.postgresql.util.PSQLException) =>
          ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreements.not.deleted", resource, t.toString))
        case Failure(t) =>
          (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreements.not.deleted", resource, t.toString)))
      })
    })
  }
  
  /* ====== GET /orgs/{organization}/nodes/{node}/agreements ================================ */
  @GET
  @Operation(summary = "Returns all agreements this node is in", description = "Returns all agreements that this node is part of. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "agreements": {
    "agreementname": {
      "services": [
        {"orgid": "string",
         "url": "string"}
      ],
      "agrService": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "string"
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodeAgreementsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getAgreementsNode(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) node: String,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/nodes/${node}/agreements - By ${identity.resource}:${identity.role}")
    complete({
      db.run(NodeAgreementsTQ.getAgreements(resource).result).map({ list =>
        logger.debug(s"GET /orgs/$organization/nodes/$node/agreements result size: ${list.size}")
        val agreements: Map[String, NodeAgreement] = list.map(e => e.agId -> e.toNodeAgreement).toMap
        val code: StatusCode = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
        (code, GetNodeAgreementsResponse(agreements, 0))
      })
    })
  }
  
  def agreementsNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "agreements") {
      (organization, node) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        
        val i: Option[UUID] =
          try
            Option(Await.result(cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
              ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, something = resource_type)
            }, 15.seconds)._1)
          catch {
            case _: Throwable => None
          }
        
        delete {
          exchAuth(TNode(resource, i), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteAgreementsNode(identity, node, organization, resource)
          }
        } ~
        get {
          exchAuth(TNode(resource, i), Access.READ, validIdentity = identity) {
            _ =>
              getAgreementsNode(identity, node, organization, resource)
          }
        }
    }
}
