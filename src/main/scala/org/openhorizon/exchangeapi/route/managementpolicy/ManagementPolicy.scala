package org.openhorizon.exchangeapi.route.managementpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, parameter, path, post, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, Identity, Identity2, OrgAndId, TManagementPolicy}
import org.openhorizon.exchangeapi.table.managementpolicy.{ManagementPoliciesTQ, ManagementPolicy => MgmtPolicy}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/managementpolicies/{mgmtpolicy}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
trait ManagementPolicy extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/managementpolicies/{mgmtpolicy} ================================ */
  @GET
  @Operation(summary = "Returns a node management policy", description = "Returns the management policy with the specified id. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "mgmtpolicy", in = ParameterIn.PATH, description = "Node management policy name."),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire management policy resource will be returned.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
      "owner": "string",
      "label": "string",
      "description": "string",
      "constraints": [
        "a == b"
      ],
      "properties": [
        {
          "name": "string",
          "type": "string",
          "value": "string"
        }
      ],
      "patterns": [
        "pat1"
      ],
      "enabled": true,
      "start": "now",
      "duration": 0,
      "agentUpgradePolicy": {
        "manifest": "<org/manifestId>",
        "allowDowngrade": false
      },
      "lastUpdated": "string"
    }
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetManagementPoliciesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getManagementPolicy(@Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) managementPolicy: String,
                          @Parameter(hidden = true) organization: String,
                          @Parameter(hidden = true) resource: String): Route =
    get {
      parameter("attribute".?) {
        attribute =>
          logger.debug(s"GET /orgs/${organization}/managementpolicies/${managementPolicy}?attribute=${attribute.getOrElse("None")} - By ${identity.resource}:${identity.role}")
          
          attribute match {
            case Some(attribute) =>  // Only returning 1 attr of the managementPolicy
              val getManagementPolicyAtrribute: Query[Rep[String], String, Seq] =
                for {
                  managementPolicyAttribute: Rep[String] <-
                    if (attribute.toLowerCase == "allowdowngrade" ||
                        attribute.toLowerCase == "enabled")
                      ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                          .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                          .take(1)
                                          .map(management_policies =>
                                                 attribute.toLowerCase match {
                                                   case "allowdowngrade" => management_policies.allowDowngrade.toString()
                                                   case "enabled" => management_policies.enabled.toString()
                                                 })
                    else if (attribute.toLowerCase == "owner")
                      ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                          .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                          .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                          .on(_.owner === _._2)
                                          .take(1)
                                          .map(management_policies => (management_policies._2._1 ++ "/" ++ management_policies._2._3))
                    else if (attribute.toLowerCase == "startwindow")
                      ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                          .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                          .take(1)
                                          .map(management_policies => management_policies.startWindow.toString())
                    else
                      ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                          .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                          .take(1)
                                          .map(management_policies =>
                                                 attribute.toLowerCase match {
                                                   case "constraints" => management_policies.constraints
                                                   case "created" => management_policies.created
                                                   case "description" => management_policies.description
                                                   case "label" => management_policies.label
                                                   case "lastupdated" => management_policies.lastUpdated
                                                   case "manifest" => management_policies.manifest
                                                   case "patterns" => management_policies.patterns
                                                   case "properties" => management_policies.properties
                                                   case "start" => management_policies.start
                                                   case _ => null
                                                 })
                                            
                } yield managementPolicyAttribute
              
              complete {
                if (getManagementPolicyAtrribute == null)
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.wrong.attribute", attribute)))
                else
                  db.run(Compiled(getManagementPolicyAtrribute).result).map {
                    result =>
                      logger.debug("GET /orgs/" + organization + "/managementpolicies/" + managementPolicy + " attribute result: " + result.toString)
                      
                      if (result.nonEmpty)
                        (HttpCode.OK, GetManagementPolicyAttributeResponse(attribute, result.head))
                      else
                        (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  }
              }
    
            case _ =>  // Return the whole management policy resource
              val getManagementPolicy: Query[((Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Long]), Rep[String]), ((Boolean, String, String, String, Boolean, String, String, String, String, String, String, String, Long), String), Seq] =
                for{
                  managementPolicy: ((Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Long]), Rep[String]) <-
                    ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                        .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                        .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                        .on(_.owner === _._2)
                                        .take(1)
                                        .map(management_policies =>
                                               ((management_policies._1.allowDowngrade,
                                                 management_policies._1.constraints,
                                                 management_policies._1.created,
                                                 management_policies._1.description,
                                                 management_policies._1.enabled,
                                                 management_policies._1.label,
                                                 management_policies._1.lastUpdated,
                                                 management_policies._1.manifest,
                                                 //management_policies._1.orgid,
                                                 (management_policies._2._1 ++ "/" ++ management_policies._2._3),
                                                 management_policies._1.patterns,
                                                 management_policies._1.properties,
                                                 management_policies._1.start,
                                                 management_policies._1.startWindow),
                                                management_policies._1.managementPolicy))
                } yield managementPolicy
              
              complete {
                db.run(Compiled(getManagementPolicy).result).map {
                  result =>
                    logger.debug("GET /orgs/" + organization + "/managementpolicies result size: " + result.size)
                    implicit val formats: Formats = DefaultFormats
                    
                    if (result.nonEmpty)
                      (StatusCodes.OK, GetManagementPoliciesResponse(result.map(management_policy => management_policy._2 -> new MgmtPolicy(management_policy._1)).toMap))
                    else
                      (StatusCodes.NotFound, GetManagementPoliciesResponse())
                }
              }
          }
      }
    }

  // =========== POST /orgs/{organization}/managementpolicies/{mgmtpolicy} ===============================
  @POST
  @Operation(
    summary = "Adds a node management policy",
    description = "Creates a node management policy resource. A node management policy controls the updating of the edge node agents. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "mgmtpolicy",
        in = ParameterIn.PATH,
        description = "Management Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the management policy",
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
  ],
  "patterns": [
    "pat1"
  ],
  "enabled": true,
  "agentUpgradePolicy": {
    "atLeastVersion": "current",
    "start": "now",
    "duration": 0
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutManagementPolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
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
  def postManagementPolicy(@Parameter(hidden = true) identity: Identity2,
                           @Parameter(hidden = true) managementPolicy: String,
                           @Parameter(hidden = true) organization: String,
                           @Parameter(hidden = true) resource: String): Route =
    post {
      entity(as[PostPutManagementPolicyRequest]) {
        reqBody =>
          logger.debug(s"POST /orgs/${organization}/managementpolicies/${managementPolicy} - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val owner: Option[UUID] = identity.identifier
              db.run(ManagementPoliciesTQ.getNumOwned(owner.get).result.asTry.flatMap({
                case Success(num) =>
                  logger.debug("POST /orgs/" + organization + "/managementpolicies" + managementPolicy + " num owned by " + owner + ": " + num)
                  val numOwned: Int = num
                  val maxManagementPolicies: Int = Configuration.getConfig.getInt("api.limits.maxManagementPolicies")
                  if (maxManagementPolicies == 0 || numOwned <= maxManagementPolicies) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                    reqBody.getDbInsert(resource, organization, owner.get).asTry
                  }
                  else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.mgmtpols", maxManagementPolicies))).asTry
                case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
              }).flatMap({
                case Success(v) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("POST /orgs/" + organization + "/managementpolicies/" + managementPolicy + " result: " + v)
                  ResourceChange(0L, organization, managementPolicy, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.CREATED).insert.asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/managementpolicies/" + managementPolicy + " updated in changes table: " + v)
                  // TODO: if (owner.isDefined) AuthCache.putManagementPolicyOwner(resource, owner.get) // currently only users are allowed to update management policy resources, so owner should never be blank
                  // TODO: AuthCache.putManagementPolicyIsPublic(resource, isPublic = false)
                  cacheResourceOwnership.put(organization, managementPolicy, "management_policy")((identity.identifier.getOrElse(identity.owner.get), false), ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds))
                  
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("mgmtpol.created", resource)))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("mgmtpol.already.exists", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("mgmtpol.not.created", resource, t.getMessage))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.not.created", resource, t.getMessage)))
              })
            })
          }
      }
    }

  // =========== PUT /orgs/{organization}/managementpolicies/{policy} ===============================
  @PUT
  @Operation(
    summary = "Updates a node management policy",
    description = "Updates a node management policy resource. A node management policy controls the updating of the edge node agents. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "mgmtpolicy",
        in = ParameterIn.PATH,
        description = "Management Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the management policy",
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
  ],
  "patterns": [
    "pat1"
  ],
  "enabled": true,
  "agentUpgradePolicy": {
    "atLeastVersion": "current",
    "start": "now",
    "duration": 0
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutManagementPolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
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
  def putManagementPolicy(@Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) managementPolicy: String,
                          @Parameter(hidden = true) organization: String,
                          @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PostPutManagementPolicyRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/${organization}/managementpolicies/${managementPolicy} - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val owner: Option[UUID] = identity.identifier
              db.run(reqBody.getDbUpdate(resource, organization, owner.get).asTry.flatMap({
                case Success(v) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/managementpolicies/" + managementPolicy + " result: " + v)
                  ResourceChange(0L, organization, managementPolicy, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.MODIFIED).insert.asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/managementpolicies/" + managementPolicy + " updated in changes table: " + v)
                  // TODO: if (owner.isDefined) AuthCache.putManagementPolicyOwner(resource, owner) // currently only users are allowed to update management policy resources, so owner should never be blank
                  // TODO: AuthCache.putManagementPolicyIsPublic(resource, isPublic = false)
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("mgmtpol.updated", resource)))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("mgmtpol.already.exists", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("mgmtpol.not.created", resource, t.getMessage))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.not.created", resource, t.getMessage)))
              })
            })
          }
      }
    }

  // =========== DELETE /orgs/{organization}/managementpolicies/{mgmtpolicy} ===============================
  @DELETE
  @Operation(summary = "Deletes a management policy", description = "Deletes a management policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "mgmtpolicy", in = ParameterIn.PATH, description = "Management Policy name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteManagementPolicy(@Parameter(hidden = true) identity: Identity2,
                             @Parameter(hidden = true) managementPolicy: String,
                             @Parameter(hidden = true) organization: String,
                             @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/managementpolicies/$managementPolicy - By ${identity.resource}:${identity.role}")
      complete({
        db.run(ManagementPoliciesTQ.getManagementPolicy(resource).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/managementpolicies/" + managementPolicy + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // TODO: AuthCache.removeManagementPolicyOwner(resource)
              // TODO: AuthCache.removeManagementPolicyIsPublic(resource)
              ResourceChange(0L, organization, managementPolicy, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("management.policy.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + organization + "/managementpolicies/" + managementPolicy + " updated in changes table: " + v)
            
            cacheResourceOwnership.remove(organization, managementPolicy, "management_policy")
            
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("management.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("management.policy.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("management.policy.not.deleted", resource, t.toString)))
        })
      })
    }
  
  def managementPolicy(identity: Identity2): Route =
    path("orgs" / Segment / "managementpolicies" / Segment) {
      (organization, managementPolicy) =>
        val resource: String = OrgAndId(organization, managementPolicy).toString
        val resource_type: String = "management_policy"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, managementPolicy, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, something = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          (delete | put) {
            exchAuth(TManagementPolicy(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteManagementPolicy(identity, managementPolicy, organization, resource) ~
                putManagementPolicy(identity, managementPolicy, organization, resource)
            }
          } ~
          get {
            exchAuth(TManagementPolicy(resource, owningResourceIdentity), Access.READ, validIdentity = identity) {
              _ =>
                getManagementPolicy(identity, managementPolicy, organization, resource)
            }
          } ~
          post {
            exchAuth(TManagementPolicy(resource, owningResourceIdentity), Access.CREATE, validIdentity = identity) {
              _ =>
                postManagementPolicy(identity, managementPolicy, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
