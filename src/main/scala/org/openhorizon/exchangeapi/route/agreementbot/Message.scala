package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route, ValidationRejection}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.auth.DBProcessingError
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsg, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthenticationSupport, BadInputRejection, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, OrgAndId, TAgbot}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/msgs/{message}")
trait Message extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // Handles NumberFormatException thrown when converting rawMessage.toInt.
  // Boilerplate for re-grabbing rest api parameter for message again for our error response body.
  private val messageExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case (e: NumberFormatException) =>
        extractUnmatchedPath {
          _ =>
            path("orgs" / Segment / "agbots" / Segment / "msgs" / Segment) {
              (_, _, BadMessageValue) =>
                complete((HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", "message", BadMessageValue))))
            }
        }
  }
  
  
  // =========== DELETE /orgs/{organization}/agbots/{agreementbot}/msgs/{message} ===========================
  @DELETE
  @Path("")
  @Operation(summary = "Deletes a Message of an Agreement Bot (AgBot)",
             description = "This should be done by the AgBot after each Message is read. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "message", in = ParameterIn.PATH, description = "Message identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def deleteMessage(agreementBot: String,
                    msgId: Int,
                    organization: String,
                    resource: String): Route =
    complete({
      //try {
      //val msgId =  msgIdStr.toInt   // this can throw an exception, that's why this whole section is in a try/catch
        db.run(AgbotMsgsTQ.getMsg(resource, msgId).delete.asTry)
          .map({
            case Success(v) =>
              logger.debug("DELETE /orgs/" + organization + "/agbots/" + agreementBot + "/msgs/" + msgId + " updated in changes table: " + v)
              (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.message.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.message.not.deleted", msgId, resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.deleted", msgId, resource, t.toString)))
          })
        //} catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
      })
    
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/msgs/{message} ===============================
  @GET
  @Path("")
  @Operation(description = "Deleted/post-TTL (Time To Live) Messages will not be returned. Can be run by a User or the AgBot.",
             parameters =
               Array(new Parameter(description = "Agreement Bot identifier",
                                   in = ParameterIn.PATH,
                                   name = "agreementbot",
                                   required = true),
                     new Parameter(description = "Message identifier.",
                                   in = ParameterIn.PATH,
                                   name = "message",
                                   required = true),
                     new Parameter(description = "Organization identifier",
                                   in = ParameterIn.PATH,
                                   name = "organization",
                                   required = true)),
             responses =
               Array(new responses.ApiResponse(content =
                 Array(new Content(mediaType = "application/json",
                                   schema =
                                     new Schema(implementation = classOf[GetAgbotMsgsResponse]))),
                                               description = "response body",
                                               responseCode = "200"),
                     new responses.ApiResponse(description = "bad input",
                                               responseCode = "400"),
                     new responses.ApiResponse(description = "invalid credentials",
                                               responseCode = "401"),
                     new responses.ApiResponse(description = "access denied",
                                               responseCode = "403"),
                     new responses.ApiResponse(description = "not found",
                                               responseCode = "404")),
             summary = "Returns A specific Message that has been sent to this Agreement Bot (AgBot).")
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def getMessage(agreementBot: String,
                 message: Int,
                 organization: String,
                 resource: String): Route =
    complete({
      db.run(AgbotMsgsTQ.getMsg(agbotId = resource,
                                msgId = message)
                        .result
                        .map(
                          result =>
                            GetAgbotMsgsResponse(lastIndex = 0,
                                                 messages =
                                                   result.map(
                                                     message =>
                                                       AgbotMsg(message = message.message,
                                                                msgId = message.msgId,
                                                                nodeId = message.nodeId,
                                                                nodePubKey = message.nodePubKey,
                                                                timeExpires = message.timeExpires,
                                                                timeSent = message.timeSent)).toList))
                        .asTry)
        .map({
          case Success(result) =>
            if(result.messages.nonEmpty)
              (HttpCode.OK, result)
            else
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
          case Failure(t) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
        })
    })
  
  
  
  def messageAgreementBot: Route =
    handleExceptions(messageExceptionHandler) {
      path("orgs" / Segment / "agbots" / Segment / "msgs" / Segment) {
        (organization,
         agreementBot,
         rawMessage) =>
           val message: Int = rawMessage.toInt // Throws NumberFormatException
           val resource: String = OrgAndId(organization, agreementBot).toString
           
           get {
             exchAuth(TAgbot(resource), Access.READ) {
               _ =>
                 getMessage(agreementBot, message, organization, resource)
             }
           } ~
           delete {
             exchAuth(TAgbot(resource), Access.WRITE) {
               _ =>
                deleteMessage(agreementBot, message, organization, resource)
             }
           }
      }
    }
}
