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
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern.{AgbotPattern, AgbotPatternsTQ}
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.table
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/agbots/{agreementbot}/patterns/{deploymentpattern}")
trait DeploymentPattern extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/patterns/{deploymentpattern} ===============================
  @DELETE
  @Operation(summary = "Deletes a Deployment Pattern of an Agreement Bot",
             description = "Deletes a Deployment Pattern that this Agreement Bot (AgBot) was serving. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "deploymentpattern", in = ParameterIn.PATH, description = "Deployment Pattern identifier")),
            responses =
              Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                    new responses.ApiResponse(responseCode = "403", description = "access denied"),
                    new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment pattern")
  private def deleteDeploymentPattern(@Parameter(hidden = true) agreementBot: String,
                                      @Parameter(hidden = true) deploymentPattern: String,
                                      @Parameter(hidden = true) organization: String,
                                      @Parameter(hidden = true) resource: String): Route = {
    complete({
      db.run(AgbotPatternsTQ.getPattern(resource, deploymentPattern)
                            .delete
                            .asTry
                            .flatMap({
                              case Success(v) => // Add the resource to the resourcechanges table
                                logger.debug("DELETE /agbots/" + agreementBot + "/patterns/" + deploymentPattern + " result: " + v)
                                if (v > 0) // there were no db errors, but determine if it actually found it or not
                                  resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTPATTERNS, ResChangeOperation.DELETED).insert.asTry
                                else
                                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.not.found", deploymentPattern, resource))).asTry
                              case Failure(t) =>
                                DBIO.failed(t).asTry}))
        .map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + agreementBot + "/patterns/" + deploymentPattern + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.pattern.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.deleted", deploymentPattern, resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.not.deleted", deploymentPattern, resource, t.toString)))
        })
    })
  }
  
  /* ====== GET /orgs/{organization}/agbots/{agreementbot}/patterns/{deploymentpattern} ================================ */
  @GET
  @Operation(summary = "Returns a Pattern this Agreement Bot is serving",
             description = "Returns the specified Pattern with the for the specified Agreement Bot (AgBot). The deploymentpattern should be in the form patternOrgid_pattern. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot Identifier"),
                     new Parameter(name = "deploymentpattern", in = ParameterIn.PATH, description = "Deployment Pattern Identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
  "patterns": {
    "patternname": {
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
  private def getDeploymentPattern(@Parameter(hidden = true) agreementBot: String,
                                   @Parameter(hidden = true) deploymentPattern: String,
                                   @Parameter(hidden = true) organization: String,
                                   @Parameter(hidden = true) resource: String): Route = {
    complete({
      db.run(AgbotPatternsTQ.getPattern(resource, deploymentPattern).result).map({
        list =>
          logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/patterns/$deploymentPattern result size: ${list.size}")
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
  
  
  val deploymentPatternAgreementBot: Route =
    path("orgs" / Segment / "agbots" / Segment / "patterns" / Segment) {
      (organization,
       agreementBot,
       deploymentPattern) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            _ =>
              getDeploymentPattern(agreementBot, deploymentPattern, organization, resource)
          }
        } ~
        delete {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              deleteDeploymentPattern(agreementBot, deploymentPattern, organization, resource)
          }
        }
    }
}
