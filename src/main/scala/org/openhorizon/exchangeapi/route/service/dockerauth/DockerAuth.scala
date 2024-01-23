package org.openhorizon.exchangeapi.route.service.dockerauth

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.PostPutServiceDockAuthRequest
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.dockerauth.{ServiceDockAuth, ServiceDockAuthsTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, _}

@Path("/v1/orgs/{organization}/services/{service}/dockauths/{dockauthid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker authorization")
trait DockerAuth extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/services/{service}/dockauths/{dockauthid} ================================ */
  @GET
  @Operation(summary = "Returns a docker image token for this service", description = "Returns the docker image authentication token with the specified dockauthid for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ServiceDockAuth])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getDockerAuth(dockerAuth: String,
                    organization: String,
                    resource: String,
                    service: String): Route =
    complete({
      Try(dockerAuth.toInt) match {
        case Success(dockauthId) =>
          db.run(ServiceDockAuthsTQ.getDockAuth(resource, dockauthId).result).map({ list =>
            logger.debug("GET /orgs/" + organization + "/services/" + service + "/dockauths/" + dockauthId + " result: " + list.size)
            if (list.nonEmpty) (HttpCode.OK, list.head.toServiceDockAuth)
            else (HttpCode.NOT_FOUND, list)
          })
        case Failure(t: org.postgresql.util.PSQLException) =>
          ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("dockauth.must.be.int", t.getMessage))
        case Failure(t) =>
          (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("dockauth.must.be.int", t.getMessage)))
      }
    })
  
  // =========== PUT /orgs/{organization}/services/{service}/dockauths/{dockauthid} ===============================
  @PUT
  @Operation(summary = "Updates a docker image token for the service", description = "Updates an existing docker image authentication token for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service to be updated."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    requestBody = new RequestBody(description = "See the POST route for details.", required = true, content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostPutServiceDockAuthRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putDockerAuth(dockerAuth: String,
                    organization: String,
                    resource: String,
                    service: String): Route =
    put {
      entity(as[PostPutServiceDockAuthRequest]) {
        reqBody =>
          validateWithMsg(reqBody.getAnyProblem(Some(dockerAuth))) {
            complete({
              val dockAuthId: Int = dockerAuth.toInt  // already checked that it is a valid int in validateWithMsg()
              db.run(reqBody.toServiceDockAuthRow(resource, dockAuthId).update.asTry.flatMap({
                case Success(n) =>
                  // Get the value of the public field
                  logger.debug("POST /orgs/" + organization + "/services/" + service + "/dockauths result: " + n)
                  val numUpdated: Int = n.asInstanceOf[Int] // n is an AnyRef so we have to do this to get it to an int
                  if (numUpdated > 0) ServicesTQ.getPublic(resource).result.asTry
                  else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.OK, ExchMsg.translate("dockauth.not.found", dockAuthId))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/dockauths/" + dockAuthId + " public field: " + public)
                  if (public.nonEmpty) {
                    val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                    ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                  } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/dockauths/" + dockAuthId + " updated in changes table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.updated", dockAuthId)))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, resource, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, resource, t.getMessage)))
                  else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, resource, t.getMessage)))
              })
            })
          }
      }
    }

  // =========== DELETE /orgs/{organization}/services/{service}/dockauths/{dockauthid} ===============================
  @DELETE
  @Operation(summary = "Deletes a docker image auth token of a service", description = "Deletes a docker image auth token for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteDockerAuth(dockerAuth: String,
                       organization: String,
                       resource: String,
                       service: String): Route =
    delete {
      complete({
        Try(dockerAuth.toInt) match {
          case Success(dockauthId) =>
            var storedPublicField = false
            db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
              case Success(public) =>
                // Get the value of the public field before delete
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " public field: " + public)
                if (public.nonEmpty) {
                  storedPublicField = public.head
                  ServiceDockAuthsTQ.getDockAuth(resource, dockauthId).delete.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " result: " + v)
                if (v > 0) { // there were no db errors, but determine if it actually found it or not
                  val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                  ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.DELETED).insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.dockauths.not.found", dockauthId, resource))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " updated in changes table: " + v)
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.dockauths.deleted")))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauths.not.deleted", dockauthId, resource, t.toString))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.dockauths.not.deleted", dockauthId, resource, t.toString)))
            })
          case Failure(t) =>  // the dockauth id wasn't a valid int
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "dockauthid must be an integer: " + t.getMessage))
        }
      })
    }
  
  val dockerAuth: Route =
    path("orgs" / Segment / "services" / Segment / "dockauths" / Segment) {
      (organization, service, dockerAuth) =>
        val resource: String = OrgAndId(organization, service).toString
        
        (delete | put) {
          exchAuth(TService(resource), Access.WRITE) {
            _ =>
              deleteDockerAuth(dockerAuth, organization, resource, service) ~
              putDockerAuth(dockerAuth, organization, resource, service)
          }
        } ~
        get {
          exchAuth(TService(resource),Access.READ) {
            _ =>
              getDockerAuth(dockerAuth, organization, resource, service)
          }
        }
    }
}
