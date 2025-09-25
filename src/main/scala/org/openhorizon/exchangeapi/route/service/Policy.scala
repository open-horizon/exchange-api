package org.openhorizon.exchangeapi.route.service

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceOwnership, validateWithMsg}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TService}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.service.policy.{ServicePolicy, ServicePolicyTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/services/{service}/policy")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service/policy")
trait Policy  extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/services/{service}/policy ================================ */
  @GET
  @Operation(summary = "Returns the service policy", description = "Returns the service policy. Can be run by a user, node or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ServicePolicy])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getPolicyService(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String,
                       @Parameter(hidden = true) service: String): Route = {
    logger.debug(s"GET /orgs/${organization}/services/${service}/policy - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      db.run(ServicePolicyTQ.getServicePolicy(resource).result).map({
        list =>
          logger.debug("GET /orgs/"+organization+"/services/"+service+"/policy result size: "+list.size)
          
          if (list.nonEmpty)
            (HttpCode.OK, list.head.toServicePolicy)
          else
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
      })
    })
  }
  
  // =========== PUT /orgs/{organization}/services/{service}/policy ===============================
  @PUT
  @Operation(
    summary = "Adds/updates the service policy",
    description = "Adds or updates the policy of a service. This can be called by the owning user.",
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
              value = """{
  "label": "human readable name of the service policy",
  "description": "descriptive text",
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutServicePolicyRequest])
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
  def putPolicyService(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String,
                       @Parameter(hidden = true) service: String): Route =
    put {
      entity(as[PutServicePolicyRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/${organization}/services/${service}/policy - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          validateWithMsg(reqBody.getAnyProblem) {
            
            val INSTANT: Instant = Instant.now()
            
            complete({
              db.run(reqBody.toServicePolicyRow(resource).upsert.asTry.flatMap({
                case Success(v) =>
                  // Get the value of the public field
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/policy result: " + v)
                  ServicesTQ.getPublic(resource).result.asTry
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(public) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/policy public field: " + public)
                  if (public.nonEmpty) {
                    val serviceId: String = resource.substring(resource.indexOf("/") + 1, resource.length)
                    ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEPOLICIES, ResChangeOperation.CREATEDMODIFIED, INSTANT).insert.asTry
                  } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/services/" + service + "/policy updated in changes table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("policy.added.or.updated")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("policy.not.inserted.or.updated", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("policy.not.inserted.or.updated", resource, t.toString))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("policy.not.inserted.or.updated", resource, t.getMessage)))
                  else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("policy.not.inserted.or.updated", resource, t.toString)))
              })
            })
          }
      }
    }

  // =========== DELETE /orgs/{organization}/services/{service}/policy ===============================
  @DELETE
  @Operation(summary = "Deletes the policy of a service", description = "Deletes the policy of a service. Can be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deletePolicyService(@Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) organization: String,
                          @Parameter(hidden = true) resource: String,
                          @Parameter(hidden = true) service: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/services/${service}/policy - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before doing the delete
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + "/policy public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServicePolicyTQ.getServicePolicy(resource).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + "/policy result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = resource.substring(resource.indexOf("/") + 1, resource.length)
              ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEPOLICIES, ResChangeOperation.DELETED, INSTANT).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.policy.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + organization + "/services/" + service + "/policy updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.policy.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.policy.not.deleted", resource, t.toString)))
        })
      })
    }
  
  def policyService(identity: Identity2): Route =
    path("orgs" / Segment / "services" / Segment / "policy") {
      (organization, service) =>
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
                deletePolicyService(identity, organization, resource, service) ~
                putPolicyService(identity, organization, resource, service)
            }
          } ~
          get{
            exchAuth(TService(resource, owningResourceIdentity, public), Access.READ, validIdentity = identity) {
              _ =>
                getPolicyService(identity, organization, resource, service)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, public)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity), public = public)
        }
    }
}
