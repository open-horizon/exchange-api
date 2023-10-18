package org.openhorizon.exchangeapi.route.service.key

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, entity, parameter, path, post, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.PutServiceKeyRequest
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.key.ServiceKeysTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/services/{service}/keys/{keyid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
trait Key extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/services/{service}/keys/{keyid} ================================ */
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
  def getKey(keyId: String,
             orgid: String,
             compositeId: String,
             service: String): Route =
    get {
      complete({
        db.run(ServiceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+list.size)
          // Note: both responses must be the same content type or that doesn't get set correctly
          if (list.nonEmpty) HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, list.head.key))
          else HttpResponse(status = HttpCode.NOT_FOUND, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))
        })
      })
    }

  // =========== PUT /orgs/{organization}/services/{service}/keys/{keyid} ===============================
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
  def putKey(keyId: String,
             orgid: String,
             compositeId: String,
             service: String): Route =
    put {
      extractRawBodyAsStr {
        reqBodyAsStr =>
          val reqBody: PutServiceKeyRequest = PutServiceKeyRequest(reqBodyAsStr)
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              db.run(reqBody.toServiceKeyRow(compositeId, keyId).upsert.asTry.flatMap({
                case Success(v) =>
                  // Get the value of the public field
                  logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " result: " + v)
                  ServicesTQ.getPublic(compositeId).result.asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " public field: " + public)
                  if (public.nonEmpty) {
                    val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                    ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEKEYS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                  } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " updated in changes table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                  else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
              })
            })
          }
      }
    }

  // =========== DELETE /orgs/{organization}/services/{service}/keys/{keyid} ===============================
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
  def deleteKey(key: String,
                organization: String,
                resource: String,
                service: String): Route =
    delete {
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceKeysTQ.getKey(resource, key).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /services/" + service + "/keys/" + key + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.key.not.found", key, resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/keys/" + key + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.key.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.deleted", key, resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.key.not.deleted", key, resource, t.toString)))
        })
      })
    }
  
  val key: Route =
    path("orgs" / Segment / "services" / Segment / "keys" / Segment) {
      (organization, service, key) =>
        val resource: String = OrgAndId(organization, service).toString
        
        (delete | put) {
          exchAuth(TService(resource), Access.WRITE) {
            _ =>
              deleteKey(key, organization, resource, service) ~
              putKey(key, organization, resource, service)
          }
        } ~
        get {
          exchAuth(TService(resource),Access.READ) {
            _ =>
              getKey(key, organization, resource, service)
          }
        }
    }
}
