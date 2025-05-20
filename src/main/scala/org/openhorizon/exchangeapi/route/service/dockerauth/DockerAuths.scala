package org.openhorizon.exchangeapi.route.service.dockerauth

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.PostPutServiceDockAuthRequest
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.dockerauth.{ServiceDockAuth, ServiceDockAuthsTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/services/{service}/dockauths")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker authorization")
trait DockerAuths extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/services/{service}/dockauths ===============================
  @DELETE
  @Operation(summary = "Deletes all docker image auth tokens of a service", description = "Deletes all of the current docker image auth tokens for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteDockerAuths(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource: String,
                        @Parameter(hidden = true) service: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/services/${service}/dockauths - By ${identity.resource}:${identity.role}")
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/dockauths public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceDockAuthsTQ.getDockAuths(resource).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("POST /orgs/" + organization + "/services result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.dockauths.found.for.service", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/dockauths result: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.dockauths.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauths.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.dockauths.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/services/{service}/dockauths ================================ */
  @GET
  @Operation(summary = "Returns all docker image tokens for this service", description = "Returns all the docker image authentication tokens for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
            new Content(
            examples = Array(
              new ExampleObject(
                value ="""
  [
    {
      "dockAuthId": 0,
      "registry": "string",
      "username": "string",
      "token": "string",
      "lastUpdated": "string"
    }
  ]
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[List[ServiceDockAuth]])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getDockerAuths(@Parameter(hidden = true) identity: Identity2,
                     @Parameter(hidden = true) organization: String,
                     @Parameter(hidden = true) resource: String,
                     @Parameter(hidden = true) service: String): Route = {
    logger.debug(s"GET /orgs/${organization}/services/${service}/dockauths - By ${identity.resource}:${identity.role}")
    complete({
      db.run(ServiceDockAuthsTQ.getDockAuths(resource).result).map({
        list =>
          logger.debug(s"GET /orgs/$organization/services/$service/dockauths result size: ${list.size}")
          
          val code: StatusCode =
            if(list.nonEmpty)
              StatusCodes.OK
            else
              StatusCodes.NotFound
          
          (code, list.sortWith(_.dockAuthId < _.dockAuthId).map(_.toServiceDockAuth))
      })
    })
  }
  
  // =========== POST /orgs/{organization}/services/{service}/dockauths ===============================
  @POST
  @Operation(
    summary = "Adds a docker image token for the service",
    description = "Adds a new docker image authentication token for this service. As an optimization, if a dockauth resource already exists with the same service, registry, username, and token, this method will just update that lastupdated field. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "service",
        in = ParameterIn.PATH,
        description = "ID of the service to be updated."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value =
                """{
  "registry": "myregistry.com",
  "username": "mydockeruser",
  "token": "mydockertoken"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutServiceDockAuthRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
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
  def postDockerAuths(@Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String,
                      @Parameter(hidden = true) service: String): Route =
    post {
      entity(as[PostPutServiceDockAuthRequest]) {
        reqBody =>
          logger.debug(s"POST /orgs/${organization}/services/${service}/dockauths - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem(None)) {
            complete({
              val dockAuthId = 0 // the db will choose a new id on insert
              var resultNum: Int = -1
              db.run(reqBody.getDupDockAuth(resource).result.asTry.flatMap({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/services" + service + "/dockauths find duplicate: " + v)
                  if (v.nonEmpty) ServiceDockAuthsTQ.getLastUpdatedAction(resource, v.head.dockAuthId).asTry // there was a duplicate entry, so just update its lastUpdated field
                  else reqBody.toServiceDockAuthRow(resource, dockAuthId).insert.asTry // no duplicate entry so add the one they gave us
                case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
              }).flatMap({
                case Success(n) =>
                  // Get the value of the public field
                  logger.debug("POST /orgs/" + organization + "/services/" + service + "/dockauths result: " + n)
                  resultNum = n.asInstanceOf[Int] // num is either the id that was added, or (in the dup case) the number of rows that were updated (0 or 1)
                  ServicesTQ.getPublic(resource).result.asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("POST /orgs/" + organization + "/services/" + service + "/dockauths public field: " + public)
                  if (public.nonEmpty) {
                    val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                    ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.CREATED).insert.asTry
                  } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/services/" + service + "/dockauths updated in changes table: " + v)
                  resultNum match {
                    case 0 => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("duplicate.dockauth.resource.already.exists"))) // we don't expect this, but it is possible, but only means that the lastUpdated field didn't get updated
                    case 1 => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.resource.updated"))) //someday: this can be 2 cases i dont know how to distinguish between: A) the 1st time anyone added a dockauth, or B) a dup was found and we updated it
                    case -1 => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("dockauth.unexpected"))) // this is meant to catch the case where the resultNum variable for some reason isn't set
                    case _ => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.num.added", resultNum))) // we did not find a dup, so this is the dockauth id that was added
                  }
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, resource, t.getMessage))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, resource, t.getMessage)))
                  else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, resource, t.getMessage)))
              })
            })
          }
      }
    }
  
  def dockerAuths(identity: Identity2): Route =
    path("orgs" / Segment / "services" / Segment / "dockauths") {
      (organization, service) =>
        val resource: String = OrgAndId(organization, service).toString
        
        (delete | post) {
          exchAuth(TService(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteDockerAuths(identity, organization, resource, service) ~
              postDockerAuths(identity, organization, resource, service)
          }
        } ~
        get {
          exchAuth(TService(resource),Access.READ, validIdentity = identity) {
            _ =>
              getDockerAuths(identity, organization, resource, service)
          }
        }
    }
}
