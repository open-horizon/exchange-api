package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, Path}
import org.openhorizon.exchangeapi.{auth, table}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern.{AgbotPattern, AgbotPatternsTQ}
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/patterns")
trait DeploymentPatterns extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/patterns =============================
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
  private def deleteDeploymentPatterns(agreementBot: String,
                                       organization: String,
                                       resource: String): Route =
    delete {
      complete({ // remove does *not* throw an exception if the key does not exist
        db.run(AgbotPatternsTQ.getPatterns(resource)
                              .delete
                              .asTry
                              .flatMap({
                                case Success(v) =>
                                  if (v > 0) { // there were no db errors, but determine if it actually found it or not
                                    // Add the resource to the resourcechanges table
                                    logger.debug("DELETE /agbots/" + agreementBot + "/patterns result: " + v)
                                    resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.DELETED).insert.asTry
                                  }
                                  else
                                    DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("patterns.not.found", resource))).asTry
                                case Failure(t) =>
                                  DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              logger.debug("DELETE /agbots/" + agreementBot + "/patterns updated in changes table: " + v)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("patterns.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("patterns.not.deleted", resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("patterns.not.deleted", resource, t.toString)))
          })
        }) // end of complete
    }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/patterns ================================
  @GET
  @Operation(summary = "Returns all patterns served by this agbot",
             description = "Returns all patterns that this agbot is finding nodes for to make agreements with them. Can be run by the owning user or the agbot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
                     new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the agbot.")),
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
  private def getDeploymentPatterns(agreementBot: String,
                                    organization: String,
                                    resource: String): Route = {
    complete({
      db.run(AgbotPatternsTQ.getPatterns(resource).result)
        .map({
          list =>
            logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/patterns result size: ${list.size}")
            val patterns: Map[String, AgbotPattern] = list.map(e => e.patId -> e.toAgbotPattern).toMap
            val code: StatusCode =
              if (patterns.nonEmpty)
                StatusCodes.OK
              else
                StatusCodes.NotFound
            (code, GetAgbotPatternsResponse(patterns))
        })
    })
  }
  
  // =========== POST /orgs/{organization}/agbots/{agreementbot}/patterns ===============================
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
  private def postDeploymentPatterns(agreementBot: String,
                                     organization: String,
                                     resource: String): Route =
    post {
      entity(as[PostAgbotPatternRequest]) {
        reqBody =>
            validateWithMsg(reqBody.getAnyProblem) {
              complete({
                val deploymentPattern: String = reqBody.formId
                db.run(PatternsTQ.getPattern(OrgAndId(reqBody.patternOrgid, reqBody.pattern).toString).length.result.asTry
                                 .flatMap({
                                   case Success(num) =>
                                     logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/patterns pattern validation: " + num)
                                     if (num > 0 ||
                                         reqBody.pattern == "*")
                                       reqBody.toAgbotPatternRow(resource, deploymentPattern).insert.asTry
                                     else
                                       DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
                                   case Failure(t) =>
                                     DBIO.failed(t).asTry})
                                 .flatMap({
                                   case Success(v) => // Add the resource to the resourcechanges table
                                     logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/patterns result: " + v)
                                     resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.CREATED).insert.asTry
                                   case Failure(t) =>
                                     DBIO.failed(t).asTry}))
                  .map({
                    case Success(v) =>
                      logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/patterns updated in changes table: " + v)
                      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.added", deploymentPattern)))
                    case Failure(t: org.postgresql.util.PSQLException) =>
                      if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t))
                        (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.foragbot.already.exists", deploymentPattern, resource)))
                      else if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                        (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", deploymentPattern, resource, t.getMessage)))
                      else
                        ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.inserted", deploymentPattern, resource, t.getServerErrorMessage))
                    case Failure(t) =>
                      if (t.getMessage.startsWith("Access Denied:"))
                        (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", deploymentPattern, resource, t.getMessage)))
                      else
                        (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.inserted", deploymentPattern, resource, t.getMessage)))
                  })
              }) // end of complete
            } // end of validateWithMsg
      }
    }
  
  
  val deploymentPatternsAgreementBot: Route =
    path("orgs" / Segment / "agbots" / Segment / "patterns") {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            _ =>
              getDeploymentPatterns(agreementBot, organization, resource)
          }
        } ~
        (delete | post) {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              deleteDeploymentPatterns(agreementBot, organization, resource) ~
              postDeploymentPatterns(agreementBot, organization, resource)
          }
        }
    }
}
