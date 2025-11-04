package org.openhorizon.exchangeapi.route.agreementbot.deploymentpattern

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotPatternsResponse, PostAgbotPatternRequest}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern.{AgbotPattern, AgbotPatternRow, AgbotPatterns, AgbotPatternsTQ}
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._
import slick.lifted.CompiledStreamingExecutable

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/deployment/patterns")
trait DeploymentPatterns extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/deployment/patterns =============================
  @DELETE
  @Operation(summary = "Deletes all Deployement Patterns of an Agreement Bot",
             description = "Deletes all of the current Deployment Patterns that this Agreement Bot (AgBot) was serving. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment pattern")
  def deleteDeploymentPatterns(@Parameter(hidden = true) agreementBot: String,
                               @Parameter(hidden = true) identity: Identity2,
                               @Parameter(hidden = true) organization: String,
                               @Parameter(hidden = true) resource: String): Route =
    delete {
      Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
      
      val INSTANT: Instant = Instant.now()
      
      implicit val formats: Formats = DefaultFormats
      
      /*
       * In the case of the default public Agreement Bot, only delete know managed resource that are owned or
       * administrated by the user archetype. For standard users, these are patterns they own (private or public).
       * For organization admins and non-multitenant agreement bots, its any pattern within their organization.
       */
      val deleteAllManagedDeploymentPatterns: DBIOAction[Unit, NoStream, Effect.Write with Effect] =
        for {
          numManagedDeploymentPatternsDeleted <-
            if (identity.isNode)
              DBIO.failed(new IllegalAccessException())
            else
              Compiled(AgbotPatternsTQ.filter(managedDeploymentPattern =>
                                                managedDeploymentPattern.agbotId === resource &&
                                                managedDeploymentPattern.patId.in (AgbotsTQ.filter(agreementBot => agreementBot.id === resource &&
                                                                                                                   agreementBot.orgid === organization)
                                                                                           // Public management of Deployment Patterns.
                                                                                           // TODO: Think on authorization
                                                                                           .filterIf(identity.isStandardUser)(agreementBot => agreementBot.owner === identity.identifier.get || agreementBot.orgid === "IBM")
                                                                                           .filterIf(identity.isAgbot)(agreementBot => agreementBot.id === identity.resource &&
                                                                                                                                       agreementBot.orgid === identity.organization)
                                                                                           // Public management of Deployment Patterns.
                                                                                           // TODO: Think on authorization
                                                                                           .filterIf(identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                                                                                           .map(_.id)
                                                                                           .join(AgbotPatternsTQ.filter(pattern => pattern.agbotId === resource))
                                                                                           .on(_ === _.agbotId)
                                                                                           .joinLeft(PatternsTQ.filterIf(identity.isStandardUser)(pattern => pattern.owner === identity.identifier.get)
                                                                                                               .filterIf((identity.isAgbot &&
                                                                                                                          !identity.isMultiTenantAgbot) ||
                                                                                                                         (identity.isOrgAdmin &&
                                                                                                                          (organization != "IBM" ||
                                                                                                                           identity.organization != "IBM")))(pattern => pattern.orgid === identity.organization)
                                                                                                               .map(pattern => (pattern.orgid, pattern.pattern, pattern.public)))
                                                                                           .on((managedDeploymentPatterns, patterns) =>
                                                                                                 (managedDeploymentPatterns._2.patternOrgid ++ "/" ++ managedDeploymentPatterns._2.pattern) === patterns._2 ||
                                                                                                 (managedDeploymentPatterns._2.patternOrgid === patterns._1 && managedDeploymentPatterns._2.pattern === "*"))
                                                                                           .map(_._1._2.patId)
                                                                                           .distinct)))
                                      .delete
          
          _ =
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Managed deployment patterns deleted: ${numManagedDeploymentPatternsDeleted}") }
          
          _ <-
            if (numManagedDeploymentPatternsDeleted == 0)
              DBIO.failed(new ClassNotFoundException)
            else
              DBIO.successful(())
          
          numChangeRecordsCreated <-
            ResourceChangesTQ +=
              ResourceChangeRow(category    = ResChangeCategory.AGBOT.toString,
                                changeId    = 0L,
                                id          = agreementBot,
                                lastUpdated = INSTANT,
                                operation   = ResChangeOperation.DELETED.toString,
                                orgId       = organization,
                                public      = "false",
                                resource    = ResChangeResource.AGBOTPATTERNS.toString)
          
          _ =
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes logged:                      ${numChangeRecordsCreated}") }
        } yield ()
        
        complete {
          db.run(deleteAllManagedDeploymentPatterns.transactionally.asTry)
            .map {
              case Success(_) =>
                (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("patterns.deleted")))
              case Failure(exception: IllegalAccessException) =>
                Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("patterns.not.deleted", resource, exception.getMessage))))}") }
                (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("patterns.not.deleted", resource, exception.getMessage)))
              case Failure(exception: ClassNotFoundException) =>
                Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.NotFound, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("patterns.not.deleted", resource, exception.getMessage))))}") }
                (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("patterns.not.deleted", resource, exception.getMessage)))
              case Failure(exception: org.postgresql.util.PSQLException) =>
                Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("patterns.not.deleted", resource, exception.toString)))}") }
                ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("patterns.not.deleted", resource, exception.toString))
              case Failure(exception) =>
                Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("patterns.not.deleted", resource, exception.toString))))}") }
                (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("patterns.not.deleted", resource, exception.toString)))
            }
        }
    }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/deployment/patterns ================================
  @GET
  @Operation(summary = "Returns all patterns served by this agbot",
             description = "Returns all patterns that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.",
             parameters =
               Array(// Path parameters
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Organization identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "organization",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String])),
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Agreement Bot identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "agreementbot",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String])),
                     // Query parameters
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by a Deployment Pattern",
                                   in              = ParameterIn.QUERY,
                                   name            = "deployment_pattern",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]])),
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by the Organization of a Deployment Pattern",
                                   in              = ParameterIn.QUERY,
                                   name            = "deployment_pattern_organization",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]])),
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by the Organization of a Node",
                                   in              = ParameterIn.QUERY,
                                   name            = "node_organization",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]]))
               ),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
  "patterns": {
    "pattern1": {
      "patternOrgid": "string",
      "pattern": "string",
      "nodeOrgid": "string",
      "lastUpdated": "2019-05-14T16:34:36.295Z[UTC]"
    },
    "pattern2": {
      "patternOrgid": "string",
      "pattern": "string",
      "nodeOrgid": "string",
      "lastUpdated": "2019-05-14T16:34:36.397Z[UTC]"
    }
  }
}""")),
                                                                   mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[GetAgbotPatternsResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment pattern")
  def getDeploymentPatterns(@Parameter(hidden = true) agreementBot: String,
                            @Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String,
                            @Parameter(hidden = true) resource: String): Route = {
    parameter("deployment_pattern".?,
              "deployment_pattern_organization".?,
              "node_organization".?) {
      (deploymentPattern,
       deploymentPatternOrganization,
       nodeOrganization) =>
        Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/patterns?deployment_pattern=${deploymentPattern}&deployment_pattern_organization=${deploymentPatternOrganization}&node_organization=${nodeOrganization} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        
        val getAgbotManagedDeploymentPatterns: CompiledStreamingExecutable[Query[(AgbotPatterns, Rep[String]), (AgbotPatternRow, String), Seq], Seq[(AgbotPatternRow, String)], (AgbotPatternRow, String)] =
          for {
            managedDeploymentPatterns <-
              Compiled(AgbotsTQ.filter(agbot => agbot.id === resource &&
                                       agbot.orgid === organization)
                               .filterIf(identity.isStandardUser)(agbot => agbot.owner === identity.identifier.get || agbot.orgid === "IBM")
                               .filterIf((identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isHubAdmin || identity.isNode || identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                               .map(_.id)
                               .join(AgbotPatternsTQ.filterOpt(deploymentPattern)((pattern, deploymentPattern) => (if (deploymentPattern.contains("%")) pattern.pattern.like(deploymentPattern) else pattern.pattern === deploymentPattern))
                                                    .filterOpt(deploymentPatternOrganization)((pattern, deploymentPatternOrganization) => (if (deploymentPatternOrganization.contains("%")) pattern.patternOrgid.like(deploymentPatternOrganization) else pattern.patternOrgid === deploymentPatternOrganization))
                                                    .filterOpt(nodeOrganization)((pattern, nodeOrganization) => (if (nodeOrganization.contains("%")) pattern.nodeOrgid.like(nodeOrganization) else pattern.nodeOrgid === nodeOrganization)))
                               .on(_ === _.agbotId)
                               .sortBy(policy => (policy._2.patternOrgid.asc.nullsDefault, policy._2.pattern.asc.nullsDefault))
                               .map(policy => (policy._2, policy._2.patId)))
          } yield (managedDeploymentPatterns)
        
        complete {
          implicit val formats: Formats = DefaultFormats
          db.run(getAgbotManagedDeploymentPatterns.result.transactionally.asTry).map {
            case Success(result) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/patterns?deployment_pattern=${deploymentPattern}&deployment_pattern_organization=${deploymentPatternOrganization}&node_organization=${nodeOrganization} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result size: ${result.size}") }
              
              if(result.nonEmpty)
                (StatusCodes.OK, GetAgbotPatternsResponse(patterns = (result.map(policies => policies._2 -> new AgbotPattern(policies._1)).toMap)))
              else {
                Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/patterns?deployment_pattern=${deploymentPattern}&deployment_pattern_organization=${deploymentPatternOrganization}&node_organization=${nodeOrganization} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result.nonEmpty:${result.nonEmpty} - ${(StatusCodes.NotFound, Serialization.write(GetAgbotPatternsResponse(patterns = Map.empty[String, AgbotPattern])))}") }
                (StatusCodes.NotFound, GetAgbotPatternsResponse(patterns = Map.empty[String, AgbotPattern]))
              }
            case Failure(exception: org.postgresql.util.PSQLException) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/patterns?deployment_pattern=${deploymentPattern}&deployment_pattern_organization=${deploymentPatternOrganization}&node_organization=${nodeOrganization} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage)))}") }
              ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage))
            case Failure(exception) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/patterns?deployment_pattern=${deploymentPattern}&deployment_pattern_organization=${deploymentPatternOrganization}&node_organization=${nodeOrganization} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error"))))}") }
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
          }
        }
    }
  }
  
  // =========== POST /orgs/{organization}/agbots/{agreementbot}/deployment/patterns ===============================
  @POST
  @Operation(summary = "Adds a Deployment Pattern that the Agreement Bot should serve",
             description = "Adds a new Deployment Pattern and Organization that this Agreement Bot (AgBot) should find Nodes for to make agreements with them. This is called by the owning User or the AgBot to give their information about the Deployment Pattern.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             requestBody =
               new RequestBody(content =
                 Array(new Content(examples =
                   Array(new ExampleObject(value = """{
  "patternOrgid": "string",
  "pattern": "string",
  "nodeOrgid": "string"
}""")),
                                   mediaType = "application/json",
                                   schema = new Schema(implementation = classOf[PostAgbotPatternRequest]))),
                                   required = true),
             responses =
               Array(new responses.ApiResponse(responseCode = "201", description = "response body",
                                               content = Array(new Content(mediaType = "application/json",
                                                                           schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment pattern")
  def postDeploymentPatterns(@Parameter(hidden = true) agreementBot: String,
                             @Parameter(hidden = true) identity: Identity2,
                             @Parameter(hidden = true) organization: String,
                             @Parameter(hidden = true) resource: String): Route =
    post {
      entity(as[PostAgbotPatternRequest]) {
        reqBody =>
          Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
          Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Request { nodeOrgid:${reqBody.nodeOrgid.getOrElse("None")}, pattern:${reqBody.pattern}, patternOrgid:${reqBody.patternOrgid} }") }
          
          val INSTANT: Instant = Instant.now()
          
          val createManagedDeploymentPattern =
            for {
              authorized <-
                if (identity.isNode)
                  DBIO.failed(new IllegalAccessException())
                else
                  Compiled(AgbotsTQ.filter(agbot => agbot.id === resource &&
                                                    agbot.orgid === organization)
                                   // Public management of Deployment Patterns.
                                   // TODO: Think on authorization
                                   .filterIf(identity.isStandardUser)(agbot => agbot.owner === identity.identifier.get || agbot.orgid === "IBM")
                                   .filterIf(identity.isAgbot)(agbot => agbot.id === identity.resource &&
                                                                        agbot.orgid === identity.organization)
                                   // Public management of Deployment Patterns.
                                   // TODO: Think on authorization
                                   .filterIf(identity.isHubAdmin || identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                                   .map(_.id)
                                   .take(1)
                                   .length)
                    .result
              
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Authorized:         ${authorized == 1}") }
              
              _ <-
                if (authorized == 1)
                  DBIO.successful(())
                else
                  DBIO.failed(new IllegalAccessException())
              
              numManagedDeploymentPatternsCreated <-
                AgbotPatternsTQ +=
                  AgbotPatternRow(agbotId      = resource,
                                  lastUpdated  = INSTANT.toString,
                                  nodeOrgid    = reqBody.nodeOrgid.getOrElse(reqBody.patternOrgid),
                                  patId        = s"${reqBody.patternOrgid}_${reqBody.pattern}_${reqBody.nodeOrgid.getOrElse(reqBody.patternOrgid)}",
                                  pattern      = reqBody.pattern,
                                  patternOrgid = reqBody.patternOrgid)
              
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Managed Deployment Patterns Created: ${numManagedDeploymentPatternsCreated}") }
              
              numChangeRecordsCreated <-
                ResourceChangesTQ +=
                  ResourceChangeRow(category    = ResChangeCategory.AGBOT.toString,
                                    changeId    = 0L,
                                    id          = agreementBot,
                                    lastUpdated = INSTANT,
                                    operation   = ResChangeOperation.CREATED.toString,
                                    orgId       = organization,
                                    public      = "false",
                                    resource    = ResChangeResource.AGBOTPATTERNS.toString)
              
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes Logged:                      ${numChangeRecordsCreated}") }
              
            } yield ()
                
          complete {
            implicit val formats: Formats = DefaultFormats
            val managedPatternIdentifier: String = s"${reqBody.patternOrgid}_${reqBody.pattern}_${reqBody.nodeOrgid.getOrElse(reqBody.patternOrgid)}"
            
            db.run(createManagedDeploymentPattern.transactionally.asTry)
              .map {
                case Success(_) =>
                  (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.added", managedPatternIdentifier)))
                case Failure(exception: IllegalAccessException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage))))}") }
                  (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage)))
                case Failure(exception: NullPointerException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadRequest, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.in.exchange", managedPatternIdentifier, resource, exception.getMessage))))}") }
                  (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.in.exchange", managedPatternIdentifier, resource, exception.getMessage)))
                case Failure(exception: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isDuplicateKeyError(exception)) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Conflict, Serialization.write(ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.foragbot.already.exists", managedPatternIdentifier, resource))))}") }
                    (StatusCodes.Conflict, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.foragbot.already.exists", managedPatternIdentifier, resource)))
                  }
                  else if (ExchangePosgtresErrorHandling.isAccessDeniedError(exception)) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage)))
                  }
                  else {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getServerErrorMessage)))}") }
                    ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getServerErrorMessage))
                  }
                case Failure(exception) =>
                  if (exception.getMessage.startsWith("Access Denied:")) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage)))
                  }
                  else {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/patterns - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadRequest, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.inserted", managedPatternIdentifier, resource, exception.getMessage)))
                  }
              }
          }
      }
    }
  
  
  def deploymentPatternsAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns")) {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        val resource_type = "agreement_bot"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, agreementBot, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          get {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.READ, validIdentity = identity) {
              _ =>
                getDeploymentPatterns(agreementBot, identity, organization, resource)
            }
          } ~
          (delete | post) {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteDeploymentPatterns(agreementBot, identity, organization, resource) ~
                postDeploymentPatterns(agreementBot, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
