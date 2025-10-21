package org.openhorizon.exchangeapi.route.service.key

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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.PutServiceKeyRequest
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.key.ServiceKeysTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/services/{service}/keys/{keyid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
trait Key extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/services/{service}/keys/{key} ================================ */
  @GET
  @Operation(summary = "Returns a key/cert for this service", description = "Returns the signing public key/cert with the specified keyid for this service. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "Key Id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getKeyService(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) key: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String,
                    @Parameter(hidden = true) service: String): Route =
    get {
      logger.debug(s"GET /orgs/${organization}/services/${service}/keys/${key} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        db.run(ServiceKeysTQ.getKey(resource, key).result).map({ list =>
          logger.debug("GET /orgs/"+organization+"/services/"+service+"/keys/"+key+" result: "+list.size)
          // Note: both responses must be the same content type or that doesn't get set correctly
          if (list.nonEmpty) HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, list.head.key))
          else HttpResponse(status = StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))
        })
      })
    }

  // =========== PUT /orgs/{organization}/services/{service}/keys/{key} ===============================
  @PUT
  @Operation(summary = "Adds/updates a key/cert for the service", description = "Adds a new signing public key/cert, or updates an existing key/cert, for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service to be updated."),
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
        schema = new Schema(implementation = classOf[PutServiceKeyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putKeyService(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) key: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String,
                    @Parameter(hidden = true) service: String): Route =
    put {
      logger.debug(s"PUT /orgs/${organization}/services/${service}/keys/${key} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      val INSTANT: Instant = Instant.now()
      
      extractRawBodyAsStr {
        reqBodyAsStr =>
          val reqBody: PutServiceKeyRequest = PutServiceKeyRequest(reqBodyAsStr)
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              db.run(reqBody.toServiceKeyRow(resource, key).upsert.asTry.flatMap({
                case Success(v) =>
                  // Get the value of the public field
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/keys/" + key + " result: " + v)
                  ServicesTQ.getPublic(resource).result.asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/keys/" + key + " public field: " + public)
                  if (public.nonEmpty) {
                    val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                    ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEKEYS, ResChangeOperation.CREATEDMODIFIED, INSTANT).insert.asTry
                  } else DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/keys/" + key + " updated in changes table: " + v)
                  (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", key, resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.inserted.or.updated", key, resource, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", key, resource, t.getMessage)))
                  else (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.key.not.inserted.or.updated", key, resource, t.getMessage)))
              })
            })
          }
      }
    }

  // =========== DELETE /orgs/{organization}/services/{service}/keys/{key} ===============================
  @DELETE
  @Operation(summary = "Deletes a key of a service", description = "Deletes a key/cert for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteKeyService(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) key: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String,
                       @Parameter(hidden = true) service: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/services/${service}/keys/${key} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceKeysTQ.getKey(resource, key).delete.asTry
            } else DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /services/" + service + "/keys/" + key + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEKEYS, ResChangeOperation.DELETED, INSTANT).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("service.key.not.found", key, resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/keys/" + key + " updated in changes table: " + v)
            (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.key.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.deleted", key, resource, t.toString))
          case Failure(t) =>
            (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.key.not.deleted", key, resource, t.toString)))
        })
      })
    }
  
  def keyService(identity: Identity2): Route =
    path("orgs" / Segment / "services" / Segment / "keys" / Segment) {
      (organization, service, key) =>
        val resource: String = OrgAndId(organization, service).toString
        val resource_type: String = "service"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, service, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None, public: Boolean = false): Route =
          (delete | put) {
            exchAuth(TService(resource, owningResourceIdentity, public), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteKeyService(identity, key, organization, resource, service) ~
                putKeyService(identity, key, organization, resource, service)
            }
          } ~
          get {
            exchAuth(TService(resource, owningResourceIdentity, public),Access.READ, validIdentity = identity) {
              _ =>
                getKeyService(identity, key, organization, resource, service)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, public)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity), public = public)
        }
    }
}
