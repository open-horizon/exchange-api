package org.openhorizon.exchangeapi.route.node.agreement

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, parameter, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.{GetNodeAgreementsResponse, PutNodeAgreementRequest}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreement, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/nodes/{node}/agreements/{agid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
trait Agreement extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/nodes/{node}/agreements/{agid} ================================ */
  @GET
  @Operation(summary = "Returns an agreement for a node", description = "Returns the agreement with the specified agid for the specified node id. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement.")),
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
        ))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getAgreementNode(@Parameter(hidden = true) agreement: String,
                       @Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) node: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/nodes/${node}/agreements/${agreement} - By ${identity.resource}:${identity.role}")
    complete({
      db.run(NodeAgreementsTQ.getAgreement(resource, agreement).result).map({ list =>
        logger.debug(s"GET /orgs/$organization/nodes/$node/agreements/$agreement result size: ${list.size}")
        val agreements: Map[String, NodeAgreement] = list.map(e => e.agId -> e.toNodeAgreement).toMap
        val code: StatusCode = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
        (code, GetNodeAgreementsResponse(agreements, 0))
      })
    })
  }
  
  // =========== PUT /orgs/{organization}/nodes/{node}/agreements/{agid} ===============================
  @PUT
  @Operation(
    summary = "Adds/updates an agreement of a node",
    description = "Adds a new agreement of a node, or updates an existing agreement. This is called by the node or owning user to give their information about the agreement.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "ID of the node to be updated."
      ),
      new Parameter(
        name = "agid",
        in = ParameterIn.PATH,
        description = "ID of the agreement to be added/updated."
      ),
      new Parameter(name = "noheartbeat", in = ParameterIn.QUERY, required = false, description = "If set to 'true', skip the step to update the node's lastHeartbeat field.")
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              //name = "SomeExample",
              value = """{
  "services": [
    {
     "orgid": "myorg",
     "url": "mydomain.com.rtlsdr"
    }
  ],
  "agreementService": {
    "orgid": "myorg",
    "pattern": "myorg/mypattern",
    "url": "myorg/mydomain.com.sdr"
  },
  "state": "negotiating"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeAgreementRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(
          new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse]))
        )
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  def putAgreementNode(@Parameter(hidden = true) agreement: String,
                       @Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) node: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route =
    put {
      parameter("noheartbeat".?) {
        noheartbeat =>
          entity(as[PutNodeAgreementRequest]) {
            reqBody =>
              logger.debug(s"PUT /orgs/${organization}/nodes/${node}/agreements/${agreement}?noheartbeat=${noheartbeat.getOrElse("None")} - By ${identity.resource}:${identity.role}")
              validateWithMsg(reqBody.getAnyProblem(noheartbeat)) {
                complete({
                  val noHB = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
                  val maxAgreements: Int = Configuration.getConfig.getInt("api.limits.maxAgreements")
                  val getNumOwnedDbio = if (maxAgreements == 0) DBIO.successful(0) else NodeAgreementsTQ.getNumOwned(resource).result // avoid DB read for this if there is no max
                  db.run(getNumOwnedDbio.flatMap({ xs =>
                    if (maxAgreements != 0) logger.debug("PUT /orgs/"+organization+"/nodes/"+node+"/agreements/"+agreement+" num owned: "+xs)
                    val numOwned: Int = xs
                    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
                    if (maxAgreements == 0 || numOwned <= maxAgreements) reqBody.toNodeAgreementRow(resource, agreement).upsert.asTry
                    else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.agreements.for.node", maxAgreements) )).asTry
                  }).flatMap({
                    case Success(v) =>
                      logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/agreements/" + agreement + " result: " + v)
                      NodesTQ.setLastUpdated(resource, ApiTime.nowUTC).asTry
                    case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({
                    case Success(v) =>
                      logger.debug("Update /orgs/" + organization + "/nodes/" + node + " lastUpdated result: " + v)
                      if (noHB) DBIO.successful(1).asTry  // skip updating lastHeartbeat
                      else NodesTQ.setLastHeartbeat(resource, ApiTime.nowUTC).asTry
                    case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({
                    case Success(v) =>
                      // Add the resource to the resourcechanges table
                      if (!noHB) logger.debug("Update /orgs/" + organization + "/nodes/" + node + " lastHeartbeat result: " + v)
                      ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                    case Failure(t) => DBIO.failed(t).asTry
                  })).map({
                    case Success(n) =>
                      logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/agreements/" + agreement + " updated in changes table: " + n)
                      try {
                        val numUpdated: Int = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
                        if (numUpdated > 0) {
                          (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreement.added.or.updated")))
                        } else {
                          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource)))
                        }
                      } catch {
                        case e: Exception => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.updated", resource, e)))
                      } // the specific exception is NumberFormatException
                    case Failure(t: DBProcessingError) =>
                      t.toComplete
                    case Failure(t: org.postgresql.util.PSQLException) =>
                      ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreement.not.inserted.or.updated", agreement, resource, t.toString))
                    case Failure(t) =>
                      (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.inserted.or.updated", agreement, resource, t.toString)))
                  })
                })
              }
          }
      }
    }

  // =========== DELETE /orgs/{organization}/nodes/{node}/agreements/{agid} ===============================
  @DELETE
  @Operation(summary = "Deletes an agreement of a node", description = "Deletes an agreement of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteAgreementNode(@Parameter(hidden = true) agreement: String,
                          @Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) node: String,
                          @Parameter(hidden = true) organization: String,
                          @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/agreements/${agreement} - By ${identity.resource}:${identity.role}")
      complete({
        db.run(NodeAgreementsTQ.getAgreement(resource,agreement).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /nodes/" + node + "/agreements/" + agreement + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.agreement.not.found", agreement, resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + node + "/agreements/" + agreement + " updated in changes table: " + v)
            NodesTQ.setLastUpdated(resource, ApiTime.nowUTC).asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + node + "/agreements/" + agreement + " lastUpdated field updated: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreement.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreement.not.deleted", agreement, resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.deleted", agreement, resource, t.toString)))
        })
      })
    }
  
  def agreementNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment) {
      (organization,
       node,
       agreement) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          (delete | put) {
            exchAuth(TNode(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteAgreementNode(agreement, identity, node, organization, resource) ~
                putAgreementNode(agreement, identity, node, organization, resource)
            }
          } ~
          get {
            exchAuth(TNode(resource, owningResourceIdentity),Access.READ, validIdentity = identity) {
              _ =>
                getAgreementNode(agreement, identity, node, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
