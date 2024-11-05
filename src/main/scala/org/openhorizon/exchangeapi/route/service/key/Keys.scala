package org.openhorizon.exchangeapi.route.service.key

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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TService}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.key.ServiceKeysTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/services/{service}/keys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
trait Keys extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/services/{service}/keys ===============================
  @DELETE
  @Operation(summary = "Deletes all keys of a service", description = "Deletes all of the current keys/certs for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def deleteKeysService(@Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource: String,
                        @Parameter(hidden = true) service: String): Route =
    delete {
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceKeysTQ.getKeys(resource).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /services/" + service + "/keys result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.service.keys.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/keys updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.keys.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.keys.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.keys.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/services/{service}/keys ================================ */
  @GET
  @Operation(summary = "Returns all keys/certs for this service", description = "Returns all the signing public keys/certs for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
            new Content(
            examples = Array(
              new ExampleObject(
                value = """
[
  "mykey.pem"
]
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[List[String]])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getKeysService(@Parameter(hidden = true) organization: String,
                     @Parameter(hidden = true) resource: String,
                     @Parameter(hidden = true) service: String): Route =
    get {
      complete({
        db.run(ServiceKeysTQ.getKeys(resource).result).map({ list =>
          logger.debug(s"GET /orgs/$organization/services/$service/keys result size: ${list.size}")
          val code: StatusCode = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.map(_.keyId))
        })
      })
    }
  
  val keysService: Route =
    path("orgs" / Segment / "services" / Segment / "keys") {
      (organization, service) =>
        val resource: String = OrgAndId(organization, service).toString
        
        delete {
          exchAuth(TService(resource), Access.WRITE) {
            _ =>
              deleteKeysService(organization, resource, service)
          }
        } ~
        get {
          exchAuth(TService(resource), Access.READ) {
            _ =>
              getKeysService(organization, resource, service)
          }
        }
    }
}
