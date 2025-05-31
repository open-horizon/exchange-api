package org.openhorizon.exchangeapi.route.organization

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.cloud.IbmCloudAuth
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, Identity2, TOrg}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.organization.{Org, OrgLimits, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Organization extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{organization} ================================
  @GET
  @Operation(summary = "Returns an org", description = "Returns the org with the specified id. Can be run by any user in this org or a hub admin.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire org resource will be returned.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "orgs": {
    "string" : {
      "orgType": "",
      "label": "Test Org",
      "description": "No",
      "lastUpdated": "2020-08-25T14:04:21.707Z[UTC]",
      "tags": {
        "cloud_id": ""
      },
      "limits": {
        "maxNodes": 0
      },
      "heartbeatIntervals": {
        "minInterval": 0,
        "maxInterval": 0,
        "intervalAdjustment": 0
      }
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetOrgsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getOrganization(@Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String): Route =
    {
      parameter("attribute".?) {
        attribute =>
          exchAuth(TOrg(organization), Access.READ, validIdentity = identity) {
            _ =>
            logger.debug(s"GET /orgs/$organization - By ${identity.resource}:${identity.role}")
            complete({
              attribute match {
                case Some(attr) => // Only returning 1 attr of the org
                  val q: org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api.Query[_, _, Seq] = OrgsTQ.getAttribute(organization, attr) // get the proper db query for this attribute
                  if (q == null) (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.attr.not.part.of.org", attr)))
                  else db.run(q.result).map({ list =>
                    logger.debug(s"GET /orgs/$organization attribute result: ${list.toString}")
                    val code: StatusCode = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                    // Note: scala is unhappy when db.run returns 2 different possible types, so we can't return ApiResponse in the case of not found
                    if (list.nonEmpty) (code, GetOrgAttributeResponse(attr, OrgsTQ.renderAttribute(list)))
                    else (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization)))
                  })
                
                case None => // Return the whole org resource
                  db.run(OrgsTQ.getOrgid(organization).result).map({ list =>
                    logger.debug(s"GET /orgs/$organization result size: ${list.size}")
                    val orgs: Map[String, Org] = list.map(a => a.orgId -> a.toOrg).toMap
                    val code: StatusCode = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                    (code, GetOrgsResponse(orgs, 0))
                  })
              }
            })
          }
      }
    }
  
  // ====== POST /orgs/{organization} ================================
  @POST
  @Operation(
    summary = "Adds an org",
    description = "Creates an org resource. This can only be called by the root user or a hub admin.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "ibmcloud_id": "abc123def456",
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 50
  },
  "heartbeatIntervals": {
    "minInterval": 10,
    "maxInterval": 120,
    "intervalAdjustment": 10
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutOrgRequest])
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
        responseCode = "409",
        description = "conflict (org already exists)"
      )
    )
  )
  def postOrganization(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) organization: String): Route =
    entity(as[PostPutOrgRequest]) {
      reqBody =>
        logger.debug(s"POST /orgs/$organization - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
            complete({
              db.run(reqBody.toOrgRow(organization).insert.asTry.flatMap({
                case Success(n) =>
                  // Add the resource to the resourcechanges table
                  logger.debug(s"POST /orgs/$organization result: $n")
                  ResourceChange(0L, organization, organization, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.CREATED).insert.asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(n) =>
                  logger.debug(s"POST /orgs/$organization put in changes table: $n")
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.created", organization)))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.created", organization, t.getMessage)))
                  else if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("org.already.exists", organization, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.created", organization, t.toString))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.created", organization, t.getMessage)))
                  else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.created", organization, t.toString)))
              })
            })
          }
   }
  
  // ====== PUT /orgs/{organization} ================================
  @PUT
  @Operation(summary = "Updates an org", description = "Does a full replace of an existing org. This can only be called by root, a hub admin, or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    requestBody = new RequestBody(description = "Does a full replace of an existing org.", required = true, content = Array(
      new Content(
      examples = Array(
        new ExampleObject(
          value = """{
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 50
  },
  "heartbeatIntervals": {
    "minInterval": 10,
    "maxInterval": 120,
    "intervalAdjustment": 10
  }
}
"""
        )
      ),
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PostPutOrgRequest])
    ))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putOrganization(@Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) reqBody: PostPutOrgRequest): Route =
    {
      logger.debug(s"PUT /orgs/$organization - By ${identity.resource}:${identity.role}")
     
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          db.run(reqBody.toOrgRow(organization).update.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PUT /orgs/$organization result: $n")
              if (n.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                ResourceChange(0L, organization, organization, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"PUT /orgs/$organization updated in changes table: $n")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", organization, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", organization, t.toString)))
          })
        })
      }
    }
  
  // ====== PATCH /orgs/{organization} ================================
  @PATCH
  @Operation(summary = "Updates 1 attribute of an org", description = "Updates one attribute of a org. This can only be called by root, a hub admin, or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes:", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 0
  },
  "heartbeatIntervals": {
    "minInterval": 10,
    "maxInterval": 120,
    "intervalAdjustment": 10
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutOrgRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patchOrganization(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) reqBody: PatchOrgRequest): Route =
    {
      logger.debug(s"PATCH /orgs/$organization - By ${identity.resource}:${identity.role}")
      
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(organization)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.org.attr.specified")))
          else db.run(action.transactionally.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PATCH /orgs/$organization result: $n")
              if (n.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                ResourceChange(0L, organization, organization, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.MODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"PATCH /orgs/$organization updated in changes table: $n")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.attr.updated", attrName, organization)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", organization, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", organization, t.toString)))
          })
        })
      }
    }
  
  // =========== DELETE /orgs/{organization} ===============================
  @DELETE
  @Operation(summary = "Deletes an org", description = "Deletes an org. This can only be called by root or a hub admin.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteOrganization(@Parameter(hidden = true) identity: Identity2,
                         @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"DELETE /orgs/$organization - By ${identity.resource}:${identity.role}")
      
      validate(organization != "root", ExchMsg.translate("cannot.delete.root.org")) {
        complete({
          // DB actions to get the user/agbot/node id's in this org
          var getResourceIds = DBIO.sequence(Seq(UsersTQ.map(_.username).result, AgbotsTQ.getAllAgbotsId(organization).result, NodesTQ.getAllNodesId(organization).result))
          var resourceIds: Seq[Seq[String]] = null
          var orgFound = true
          // remove does *not* throw an exception if the key does not exist
          db.run(getResourceIds.asTry.flatMap({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$organization remove from cache: users: ${v(0).size}, agbots: ${v(1).size}, nodes: ${v(2).size}")
              resourceIds = v // save for a subsequent step - this is a vector of 3 vectors
              OrgsTQ.getOrgid(organization).delete.transactionally.asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$organization result: $v")
              if (v > 0) { // there were no db errors, but determine if it actually found it or not
                ResourceChange(0L, organization, organization, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.DELETED).insert.asTry
              } else {
                orgFound = false
                DBIO.successful("no update in resourcechanges table").asTry // just give a success to get us to the next step, but notify that it wasn't added to the resourcechanges table
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$organization updated in changes table: $v")
              if (orgFound && resourceIds!=null) {
                // Loop thru user/agbot/node id's and remove them from the cache
              //  for (id <- resourceIds(0)) { /*println(s"removing $id from cache");*/ AuthCache.removeUser(id) } // users
              //  for (id <- resourceIds(1)) { /*println(s"removing $id from cache");*/ AuthCache.removeAgbotAndOwner(id) } // agbots
              //  for (id <- resourceIds(2)) { /*println(s"removing $id from cache");*/ AuthCache.removeNodeAndOwner(id) } // nodes
              //  IbmCloudAuth.clearCache() // no alternative but sledgehammer approach because the IAM cache is keyed by api key
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.deleted")))
              } else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.deleted", organization, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.deleted", organization, t.toString)))
          })
        })
      }
    }
  
  def organization(identity: Identity2): Route =
    path("orgs" / Segment) {
      organization =>
        delete {
          exchAuth(TOrg(organization), Access.DELETE_ORG, validIdentity = identity) {
            _ =>
              deleteOrganization(identity, organization)
          }
        } ~
        get {
          exchAuth(TOrg(organization), Access.READ, validIdentity = identity) {
            _ =>
              getOrganization(identity, organization)
          }
        } ~
        patch {
          entity(as[PatchOrgRequest]) {
            reqBody =>
              val access: Access.Value = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
          
              exchAuth(TOrg(organization), access, validIdentity = identity) {
                _ =>
                  patchOrganization(identity, organization, reqBody)
              }
          }
        } ~
        post {
          exchAuth(TOrg(""), Access.CREATE, validIdentity = identity) {
            _ =>
              postOrganization(identity, organization)
          }
        } ~
        put {
          entity(as[PostPutOrgRequest]) {
            reqBody =>
              val access: Access.Value = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
              
              exchAuth(TOrg(organization), access, validIdentity = identity) {
                _ =>
                  putOrganization(identity, organization, reqBody)
              }
            }
        }
    }
}
