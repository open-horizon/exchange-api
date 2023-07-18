package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, AuthenticationSupport, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, OrgAndId, TAgbot}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/agbots/{agreementbot}/heartbeat")
trait Heartbeat extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== POST /orgs/{organization}/agbots/{agreementbot}/heartbeat ===============================
  @POST
  @Operation(summary = "Tells the Exchange this Agreement Bot is still operating",
             description = "Lets the Exchange know this Agreement Bot (AgBot) is still active. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "201", description = "response body",
                                               content = Array(new Content(mediaType = "application/json",
                                                                           schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot")
  def postHeartbeat(agreementBot: String,
                    organization: String,
                    resource: String): Route = {
    logger.debug(s"Doing POST /orgs/$organization/users/$agreementBot/heartbeat")
    complete({
      db.run(AgbotsTQ.getLastHeartbeat(resource).update(ApiTime.nowUTC).asTry)
        .map({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"POST /orgs/$organization/users/$agreementBot/heartbeat result: $v")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.updated")))
            }
            else
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.not.found", resource)))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.not.updated", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.not.updated", resource, t.toString)))
        })
    })
  }
  
  val heartbeatAgreementBot: Route =
    path("orgs" / Segment / "agbots" / Segment / "heartbeat") {
      (organization, agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        post {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              postHeartbeat(agreementBot, organization, resource)
          }
        }
    }
}
