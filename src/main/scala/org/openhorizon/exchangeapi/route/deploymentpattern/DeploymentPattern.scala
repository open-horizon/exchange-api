package org.openhorizon.exchangeapi.route.deploymentpattern

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, parameter, path, post, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, BadInputException, DBProcessingError, IUser, Identity, Identity2, OrgAndId, ResourceNotFoundException, TPattern}
import org.openhorizon.exchangeapi.table.deploymentpattern.{Pattern, PatternsTQ}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth}
import scalacache.modes.scalaFuture.mode
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/patterns/{pattern}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
trait DeploymentPattern extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/patterns/{pattern} ===============================
  @DELETE
  @Operation(summary = "Deletes a pattern", description = "Deletes a pattern. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                              @Parameter(hidden = true) identity: Identity2,
                              @Parameter(hidden = true) organization: String,
                              @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/patterns/$deploymentPattern - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        // remove does *not* throw an exception if the key does not exist
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field then do the delete
            logger.debug("DELETE /orgs/" + organization + "/patterns/" + deploymentPattern + " public field is: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternsTQ.getPattern(resource).delete.transactionally.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/patterns/" + deploymentPattern + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // TODO: AuthCache.removePatternOwner(resource)
              // TODO: AuthCache.removePatternIsPublic(resource)
              ResourceChange(0L, organization, deploymentPattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERN, ResChangeOperation.DELETED, INSTANT).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            Future { logger.debug("DELETE /orgs/" + organization + "/patterns/" + deploymentPattern + " updated in changes table: " + v) }
            
            Future { cacheResourceOwnership.remove(organization, deploymentPattern, "deployment_pattern") }
            
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.id.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.id.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/patterns/{pattern} ================================ */
  @GET
  @Operation(summary = "Returns a pattern", description = "Returns the pattern with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire pattern resource will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "patterns": {
    "orgid/patternname": {
      "owner": "string",
      "label": "My Pattern",
      "description": "blah blah",
      "public": true,
      "services": [
        {
          "serviceUrl": "string",
          "serviceOrgid": "string",
          "serviceArch": "string",
          "agreementLess": false,
          "serviceVersions": [
            {
              "version": "4.5.6",
              "deployment_overrides": "string",
              "deployment_overrides_signature": "a",
              "priority": {
                "priority_value": 50,
                "retries": 1,
                "retry_durations": 3600,
                "verified_durations": 52
              },
              "upgradePolicy": {
                "lifecycle": "immediate",
                "time": "01:00AM"
              }
            }
          ],
          "dataVerification": {
            "metering": {
              "tokens": 1,
              "per_time_unit": "min",
              "notification_interval": 30
            },
            "URL": "",
            "enabled": true,
            "interval": 240,
            "check_rate": 15,
            "user": "",
            "password": ""
          },
          "nodeHealth": {
            "missing_heartbeat_interval": 600,
            "check_agreement_status": 120
          }
        }
      ],
      "userInput": [],
      "secretBinding": [],
      "agreementProtocols": [
        {
          "name": "Basic"
        }
      ],
      "lastUpdated": "2019-05-14T16:34:34.194Z[UTC]",
      "clusterNamespace": "MyNamespace"
    }
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetPatternsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                           @Parameter(hidden = true) identity: Identity2,
                           @Parameter(hidden = true) organization: String,
                           @Parameter(hidden = true) resource: String): Route =
    parameter("attribute".?) {
      attribute =>
        logger.debug(s"GET /orgs/$organization/patterns/$deploymentPattern?attribute=${attribute.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        attribute match {
          case Some(attribute) => // Only returning 1 attr of the pattern
              val getPatternAttribute: Query[Rep[String], String, Seq] =
                for {
                  patternAttribute: Rep[String] <-
                    if (attribute.toLowerCase == "owner")
                      PatternsTQ.filter(deployment_patterns => deployment_patterns.orgid === organization &&
                                                               deployment_patterns.pattern === resource)
                                .filterIf(identity.isOrgAdmin || identity.isStandardUser || (identity.isAgbot && !identity.isMultiTenantAgbot))(deployment_patterns => deployment_patterns.orgid === identity.organization ||
                                                                                                                                                                       deployment_patterns.orgid === "IBM" ||
                                                                                                                                                                       deployment_patterns.public)
                                .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                .on(_.owner === _._2)
                                .take(1)
                                .map(deployment_patterns => (deployment_patterns._2._1 ++ "/" ++ deployment_patterns._2._3))
                    else if (attribute.toLowerCase == "public")
                      PatternsTQ.filter(deployment_patterns => deployment_patterns.orgid === organization &&
                                                               deployment_patterns.pattern === resource)
                                .filterIf(identity.isOrgAdmin || identity.isStandardUser || (identity.isAgbot && !identity.isMultiTenantAgbot))(deployment_patterns => deployment_patterns.orgid === identity.organization ||
                                                                                                                                                                       deployment_patterns.orgid === "IBM" ||
                                                                                                                                                                       deployment_patterns.public)
                                .take(1)
                                .map(_.public.toString())
                    else
                      PatternsTQ.filter(deployment_patterns => deployment_patterns.orgid === organization &&
                                                               deployment_patterns.pattern === resource)
                                .filterIf(identity.isOrgAdmin || identity.isStandardUser || (identity.isAgbot && !identity.isMultiTenantAgbot))(deployment_patterns => deployment_patterns.orgid === identity.organization ||
                                                                                                                                                                       deployment_patterns.orgid === "IBM" ||
                                                                                                                                                                       deployment_patterns.public)
                                .take(1)
                                .map(deployment_patterns =>
                                       attribute.toLowerCase match {
                                         case "agreementprotocols" => deployment_patterns.agreementProtocols
                                         case "clusternamespace" => deployment_patterns.clusterNamespace.getOrElse("")
                                         case "description" => deployment_patterns.description
                                         case "label" => deployment_patterns.label
                                         case "lastpdated" => deployment_patterns.lastUpdated
                                         case "secretbinding" => deployment_patterns.secretBinding
                                         case "services" => deployment_patterns.services
                                         case "userinput" => deployment_patterns.userInput
                                         case _ => null
                                       })
                } yield patternAttribute
                
            complete {
              if (getPatternAttribute == null)
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.attr.not.in.pattern", attribute)))
              else
                db.run(Compiled(getPatternAttribute).result).map {
                  list =>
                    logger.debug("GET /orgs/" + organization + "/patterns/" + deploymentPattern + " attribute result: " + list.toString)
                    if (list.nonEmpty)
                      (HttpCode.OK, GetPatternAttributeResponse(attribute, list.head))
                    else
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                    
                }
            }
            
          case _ =>  // Return the whole pattern resource
            val getDeploymentPattern: Query[((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]), ((String, Option[String], String, String, String, String, Boolean, String, String, String), String), Seq] =
              for {
                pattern: ((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]) <-
                  PatternsTQ.filter(deployment_patterns => deployment_patterns.orgid === organization &&
                                                           deployment_patterns.pattern === resource)
                            .filterIf(identity.isOrgAdmin || identity.isStandardUser || (identity.isAgbot && !identity.isMultiTenantAgbot))(deployment_patterns => deployment_patterns.orgid === identity.organization ||
                                                                                                                                                                   deployment_patterns.orgid === "IBM" ||
                                                                                                                                                                   deployment_patterns.public)
                            .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                            .on(_.owner === _._2)
                            .take(1)
                            .map(deployment_patterns =>
                                   ((deployment_patterns._1.agreementProtocols,
                                     deployment_patterns._1.clusterNamespace,
                                     deployment_patterns._1.description,
                                     deployment_patterns._1.label,
                                     deployment_patterns._1.lastUpdated,
                                     (deployment_patterns._2._1 ++ "/" ++ deployment_patterns._2._3),
                                     // deployment_patterns._1.orgid,
                                     deployment_patterns._1.public,
                                     deployment_patterns._1.secretBinding,
                                     deployment_patterns._1.services,
                                     deployment_patterns._1.userInput),
                                    deployment_patterns._1.pattern))
                } yield pattern
            
            complete {
              db.run(Compiled(getDeploymentPattern).result).map {
                result =>
                  logger.debug("GET /orgs/" + organization + "/patterns result size: " + result.size)
                  implicit val formats: Formats = DefaultFormats
                  
                  if (result.nonEmpty)
                    (StatusCodes.OK, GetPatternsResponse(result.map(patterns => patterns._2 -> new Pattern(patterns._1)).toMap))
                  else
                    (StatusCodes.NotFound, GetPatternsResponse())
              }
            }
        }
    }
  
  // =========== PATCH /orgs/{organization}/patterns/{pattern} ===============================
  @PATCH
  @Operation(summary = "Updates 1 attribute of a pattern", description = "Updates one attribute of a pattern. This can only be called by the user that originally created this pattern resource.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
          "priority": {
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          "upgradePolicy": {
            "lifecycle": "immediate",
            "time": "01:00AM"
          }
        }
      ],
      "dataVerification": {
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchPatternRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patchDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                             @Parameter(hidden = true) identity: Identity2,
                             @Parameter(hidden = true) organization: String,
                             @Parameter(hidden = true) resource: String): Route =
    patch {
      entity(as[PatchPatternRequest]) {
        reqBody =>
          logger.debug(s"PATCH /orgs/$organization/patterns/$deploymentPattern - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          validateWithMsg(reqBody.getAnyProblem) {
            
            val INSTANT: Instant = Instant.now()
            
            complete({
              val (action, attrName) = reqBody.getDbUpdate(resource, organization)
              var storedPatternPublic = false
              if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.pattern.attribute.specified")))
              else {
                val (valServiceIdActions, svcRefs) = if (attrName == "services") PatternsTQ.validateServiceIds(reqBody.services.get, List())
                else if (attrName == "userInput") PatternsTQ.validateServiceIds(List(), reqBody.userInput.get)
                else (DBIO.successful(Vector()), Vector())
                db.run(valServiceIdActions.asTry.flatMap({
                  case Success(v) =>
                    logger.debug("PATCH /orgs/" + organization + "/patterns" + deploymentPattern + " service validation: " + v)
                    var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                    breakable {
                      for ((len, index) <- v.zipWithIndex) {
                        if (len <= 0) {
                          invalidIndex = index
                          break()
                        }
                      }
                    }
                    if (invalidIndex < 0) PatternsTQ.getPublic(resource).result.asTry //getting public field from pattern
                    else {
                      val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                      else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                      DBIO.failed(new Throwable(errStr)).asTry
                    }
                  case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
                }).flatMap({
                  case Success(patternPublic) =>
                    logger.debug("PATCH /orgs/" + organization + "/patterns" + deploymentPattern + " checking public field of " + resource + ": " + patternPublic)
                    val public: Seq[Boolean] = patternPublic
                    if (public.nonEmpty) {
                      val publicField: Boolean = reqBody.public.getOrElse(false)
                      storedPatternPublic = public.head
                      if ((public.head && publicField) || publicField) { // pattern is public so need to check owner
                        OrgsTQ.getOrgType(organization).result.asTry
                      } else { // pattern isn't public so skip orgType check
                        DBIO.successful(Vector("IBM")).asTry
                      }
                    } else {
                      DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("pattern.id.not.found", resource))).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                }).flatMap({
                  case Success(patternOrg) =>
                    logger.debug("PATCH /orgs/" + organization + "/patterns" + deploymentPattern + " checking orgType of " + organization + ": " + patternOrg)
                    if (patternOrg.head == "IBM") { // only patterns of orgType "IBM" can be public
                      action.transactionally.asTry
                    }
                    else {
                      DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                }).flatMap({
                  case Success(v) =>
                    // Add the resource to the resourcechanges table
                    logger.debug("PATCH /orgs/" + organization + "/patterns/" + deploymentPattern + " result: " + v)
                    val numUpdated: Int = v.asInstanceOf[Int] // v comes to us as type Any
                    if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                      // TODO: if (attrName == "public") AuthCache.putPatternIsPublic(resource, reqBody.public.getOrElse(false))
                      var publicField = false
                      if (reqBody.public.isDefined) {
                        publicField = reqBody.public.getOrElse(false)
                      }
                      else {
                        publicField = storedPatternPublic
                      }
                      ResourceChange(0L, organization, deploymentPattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.MODIFIED, INSTANT).insert.asTry
                    } else {
                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.attribute.not.update", attrName, resource))).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                })).map({
                  case Success(v) =>
                    logger.debug("PATCH /orgs/" + organization + "/patterns/" + deploymentPattern + " updated in changes table: " + v)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.attribute.not.update", attrName, resource)))
                  case Failure(t: DBProcessingError) =>
                    t.toComplete
                  case Failure(_: ResourceNotFoundException) =>
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", resource)))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.updated", resource, t.getMessage))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.updated", resource, t.getMessage)))
                })
              }
            })
          }
      }
    }
  
  // =========== POST /orgs/{organization}/patterns/{pattern} ===============================
  @POST
  @Operation(
    summary = "Adds a pattern",
    description = "Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
          "priority": {
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          "upgradePolicy": {
            "lifecycle": "immediate",
            "time": "01:00AM"
          }
        }
      ],
      "dataVerification": {
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutPatternRequest])
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
  def postDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                            @Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String,
                            @Parameter(hidden = true) resource: String): Route =
    entity(as[PostPutPatternRequest]) {
      reqBody =>
        logger.debug(s"POST /orgs/$organization/patterns/$deploymentPattern - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        validateWithMsg(reqBody.getAnyProblem) {
          
          val INSTANT: Instant = Instant.now()
          
          complete({
            val owner: Option[UUID] = identity.identifier
            // Get optional agbots that should be updated with this new pattern
            val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
            db.run(valServiceIdActions.asTry.flatMap({
              case Success(v) =>
                logger.debug("POST /orgs/" + organization + "/patterns" + deploymentPattern + " service validation: " + v)
                var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break()
                    }
                  }
                }
                if (invalidIndex < 0) OrgsTQ.getAttribute(organization, "orgType").result.asTry //getting orgType from orgid
                else {
                  val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(orgName) =>
                logger.debug("POST /orgs/" + organization + "/patterns" + deploymentPattern + " checking public field and orgType of " + resource + ": " + orgName)
                val orgType: Seq[Any] = orgName
                val publicField: Boolean = reqBody.public.getOrElse(false)
                if ((publicField && orgType.head == "IBM") || !publicField) { // pattern is public and owner is IBM so ok, or pattern isn't public at all so ok
                  PatternsTQ.getNumOwned(identity.identifier.getOrElse(identity.owner.get)).result.asTry
                }
                else DBIO.failed(BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(num) =>
                logger.debug("POST /orgs/" + organization + "/patterns" + deploymentPattern + " num owned by " + owner + ": " + num)
                val numOwned: Int = num
                val maxPatterns: Int = Configuration.getConfig.getInt("api.limits.maxPatterns")
                if (maxPatterns == 0 || numOwned <= maxPatterns) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                  reqBody.toPatternRow(resource, organization, identity.identifier.getOrElse(identity.owner.get)).insert.asTry
                }
                else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.max.patterns", maxPatterns))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("POST /orgs/" + organization + "/patterns/" + deploymentPattern + " result: " + v)
                val publicField: Boolean = reqBody.public.getOrElse(false)
                ResourceChange(0L, organization, deploymentPattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.CREATED, INSTANT).insert.asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("POST /orgs/" + organization + "/patterns/" + deploymentPattern + " updated in changes table: " + v)
                //if (owner.isDefined) AuthCache.putPatternOwner(resource, owner) // currently only users are allowed to update pattern resources, so owner should never be blank
                //AuthCache.putPatternIsPublic(resource, reqBody.public.getOrElse(false))
                cacheResourceOwnership.put(organization, deploymentPattern, "deployment_pattern")((identity.identifier.getOrElse(identity.owner.get), reqBody.public.getOrElse(false)), ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds))
                
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.created", resource)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.already.exists", resource, t.getMessage)))
                else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.created", resource, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.created", resource, t.getMessage)))
            })
          })
        }
    }
  
  // =========== PUT /orgs/{organization}/patterns/{pattern} ===============================
  @PUT
  @Operation(summary = "Adds a pattern", description = "Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
          "priority": {
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          "upgradePolicy": {
            "lifecycle": "immediate",
            "time": "01:00AM"
          }
        }
      ],
      "dataVerification": {
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutPatternRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                           @Parameter(hidden = true) identity: Identity2,
                           @Parameter(hidden = true) organization: String,
                           @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PostPutPatternRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/$organization/patterns/$deploymentPattern - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          
          validateWithMsg(reqBody.getAnyProblem) {
            
            val INSTANT: Instant = Instant.now()
            
            complete({
              val owner: Option[UUID] = identity.identifier
              var storedPatternPublic = false
              val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
              db.run(valServiceIdActions.asTry.flatMap({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/patterns" + deploymentPattern + " service validation: " + v)
                  var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                  breakable {
                    for ((len, index) <- v.zipWithIndex) {
                      if (len <= 0) {
                        invalidIndex = index
                        break()
                      }
                    }
                  }
                  if (invalidIndex < 0) PatternsTQ.getPublic(resource).result.asTry //getting public field from pattern
                  else {
                    val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                    else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                    DBIO.failed(new Throwable(errStr)).asTry
                  }
                case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
              }).flatMap({ xs =>
                logger.debug("PUT /orgs/"+organization+"/patterns"+deploymentPattern+" checking public field of "+resource+": "+xs)
                xs match {
                  case Success(patternPublic) => val public: Seq[Boolean] = patternPublic
                    if(public.nonEmpty){
                      storedPatternPublic = public.head
                      if (public.head || reqBody.public.getOrElse(false)) {    // pattern is public so need to check orgType
                        OrgsTQ.getOrgType(organization).result.asTry // should return a vector of Strings
                      } else { // pattern isn't public so skip orgType check
                        DBIO.successful(Vector("IBM")).asTry
                      }
                    } else {
                      DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("pattern.id.not.found", resource))).asTry
                    }
                  case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
                }
              }).flatMap({
                case Success(orgTypes) =>
                  logger.debug("PUT /orgs/" + organization + "/patterns" + deploymentPattern + " checking orgType of " + organization + ": " + orgTypes)
                  if (orgTypes.head == "IBM") { // only patterns of orgType "IBM" can be public
                    reqBody.toPatternRow(resource, organization, identity.identifier.getOrElse(identity.owner.get)).update.asTry
                  } else {
                    DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
                  }
                case Failure(t) => DBIO.failed(t).asTry
              }).flatMap({
                case Success(n) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/patterns/" + deploymentPattern + " result: " + n)
                  val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
                  if (numUpdated > 0) {
                    var publicField = false
                    if (reqBody.public.isDefined) {
                      publicField = reqBody.public.getOrElse(false)
                      cacheResourceOwnership.put(organization, deploymentPattern, "deployment_pattern")((identity.identifier.getOrElse(identity.owner.get), publicField), ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds))
                    }
                    else {
                      publicField = storedPatternPublic
                    }
                    ResourceChange(0L, organization, deploymentPattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.CREATEDMODIFIED, INSTANT).insert.asTry
                  } else {
                    DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", resource))).asTry
                  }
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/patterns/" + deploymentPattern + " updated in changes table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.updated")))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: ResourceNotFoundException) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.updated", resource, t.getMessage))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.updated", resource, t.getMessage)))
              })
            })
          }
      }
    }
  
  def deploymentPattern(identity: Identity2): Route =
    path("orgs" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns") / Segment) {
      (organization,
       deploymentPattern) =>
        val resource: String = OrgAndId(organization, deploymentPattern).toString
        val resource_type: String = "deployment_pattern"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, deploymentPattern, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None, public: Boolean = false): Route =
          (delete | patch | put) {
            exchAuth(TPattern(resource, owningResourceIdentity, public), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteDeploymentPattern(deploymentPattern, identity, organization, resource) ~
                patchDeploymentPattern(deploymentPattern, identity, organization, resource) ~
                putDeploymentPattern(deploymentPattern, identity, organization, resource)
            }
          } ~
          get {
            exchAuth(TPattern(resource, owningResourceIdentity, public), Access.READ, validIdentity = identity) {
              _ =>
                getDeploymentPattern(deploymentPattern, identity, organization, resource)
            }
          } ~
          post {
            exchAuth(TPattern(resource, owningResourceIdentity, public), Access.CREATE, validIdentity = identity) {
              _ =>
                postDeploymentPattern(deploymentPattern, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, public)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity), public = public)
        }
    }
}
