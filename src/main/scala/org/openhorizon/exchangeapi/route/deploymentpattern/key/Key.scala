package org.openhorizon.exchangeapi.route.deploymentpattern.key

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, get, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TPattern}
import org.openhorizon.exchangeapi.route.deploymentpattern.PutPatternKeyRequest
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.key.PatternKeysTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/deployment/patterns/{pattern}/keys/{keyid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
trait Key extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/deployment/patterns/{pattern}/keys/{keyid} ===============================
  @DELETE
  @Operation(summary = "Deletes a key of a pattern", description = "Deletes a key/cert for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteKeyDeploymentPattern(@Parameter(hidden = true) pattern: String,
                                 @Parameter(hidden = true) identity: Identity2,
                                 @Parameter(hidden = true) keyId: String,
                                 @Parameter(hidden = true) orgid: String,
                                 @Parameter(hidden = true) compositeId: String): Route =
    delete {
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the public field before doing delete
            logger.debug("DELETE /patterns/" + pattern + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternKeysTQ.getKey(compositeId, keyId).delete.asTry
            } else DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /patterns/" + pattern + "/keys/" + keyId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.DELETED, INSTANT).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.key.not.found", keyId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /patterns/" + pattern + "/keys result: " + v)
            (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.key.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.key.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.key.not.deleted", compositeId, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/deployment/patterns/{pattern}/keys/{keyid} ================================ */
  @GET
  @Operation(summary = "Returns a key/cert for this pattern", description = "Returns the signing public key/cert with the specified keyid for this pattern. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "Signing public key/certificate identifier.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getKeyDeploymentPattern(@Parameter(hidden = true) pattern: String,
                              @Parameter(hidden = true) identity: Identity2,
                              @Parameter(hidden = true) keyId: String,
                              @Parameter(hidden = true) orgid: String,
                              @Parameter(hidden = true) compositeId: String): Route =
    {
      complete({
        db.run(PatternKeysTQ.getKey(compositeId, keyId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" result: "+list.size)
          // Note: both responses must be the same content type or that doesn't get set correctly
          if (list.nonEmpty) HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, list.head.key))
          else HttpResponse(status = StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))
          // this doesn't seem to set the content type away from application/json
          //if (list.nonEmpty) (StatusCodes.OK, List(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)), list.head.key)
          //else (StatusCodes.NotFound, List(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)), "")
          //if (list.nonEmpty) (StatusCodes.OK, list.head.key)
          //else (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("key.not.found", keyId)))
        })
      })
    }
  
  // =========== PUT /orgs/{organization}/deployment/patterns/{pattern}/keys/{keyid} ===============================
  @PUT
  @Operation(summary = "Adds/updates a key/cert for the pattern", description = "Adds a new signing public key/cert, or updates an existing key/cert, for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "ID of the pattern to be updated."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key to be added/updated.")),
    requestBody = new RequestBody(description = "Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "key": "string"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PutPatternKeyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putKeyDeploymentPattern(@Parameter(hidden = true) pattern: String,
                              @Parameter(hidden = true) identity: Identity2,
                              @Parameter(hidden = true) keyId: String,
                              @Parameter(hidden = true) orgid: String,
                              @Parameter(hidden = true) compositeId: String): Route =
    put {
      extractRawBodyAsStr {
        reqBodyAsStr =>
          val reqBody: PutPatternKeyRequest = PutPatternKeyRequest(reqBodyAsStr)
          validateWithMsg(reqBody.getAnyProblem) {
            
            val INSTANT: Instant = Instant.now()
            
            complete({
              db.run(reqBody.toPatternKeyRow(compositeId, keyId).upsert.asTry.flatMap({
                case Success(v) =>
                  // Get the value of the public field
                  logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " result: " + v)
                  PatternsTQ.getPublic(compositeId).result.asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " public field: " + public)
                  if (public.nonEmpty) {
                    val publicField: Boolean = public.head
                    ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.CREATEDMODIFIED, INSTANT).insert.asTry
                  } else DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " updated in changes table: " + v)
                  (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                  else (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
              })
            })
          }
      }
    }
  
  def keyDeploymentPattern(identity: Identity2): Route =
    path("orgs" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns") / Segment / "keys" / Segment) {
      (organization,
       deploymentPattern,
       key) =>
        val resource: String = OrgAndId(organization, deploymentPattern).toString
        val resource_type: String = "deployment_pattern"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, deploymentPattern, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None, public: Boolean = false): Route =
          (delete | put) {
            exchAuth(TPattern(resource, owningResourceIdentity, public), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteKeyDeploymentPattern(deploymentPattern, identity, key, organization, resource) ~
                putKeyDeploymentPattern(deploymentPattern, identity, key, organization, resource)
            }
          } ~
          get {
            exchAuth(TPattern(resource, owningResourceIdentity, public),Access.READ, validIdentity = identity) {
              _ =>
                getKeyDeploymentPattern(deploymentPattern, identity, key, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, public)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity), public = public)
        }
    }
}
