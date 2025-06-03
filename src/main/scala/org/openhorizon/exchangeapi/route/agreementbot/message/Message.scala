package org.openhorizon.exchangeapi.route.agreementbot.message

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.GetAgbotMsgsResponse
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsg, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
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
  def deleteMessage(@Parameter(hidden = true) agreementBot: String,
                    @Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) message: Int,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/msgs/${message} - By ${identity.resource}:${identity.role}")
    complete({
      //try {
      //val msgId =  msgIdStr.toInt   // this can throw an exception, that's why this whole section is in a try/catch
        db.run(AgbotMsgsTQ.getMsg(resource, message).delete.asTry)
          .map({
            case Success(v) =>
              logger.debug("DELETE /orgs/" + organization + "/agbots/" + agreementBot + "/msgs/" + message + " updated in changes table: " + v)
              (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.message.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.message.not.deleted", message, resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.deleted", message, resource, t.toString)))
          })
        //} catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
      })
  }
  
  
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
  def getMessage(@Parameter(hidden = true) agreementBot: String,
                 @Parameter(hidden = true) identity: Identity2,
                 @Parameter(hidden = true) message: Int,
                 @Parameter(hidden = true) organization: String,
                 @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/msgs/${message} - By ${identity.resource}:${identity.role}")
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
  }
  
  
  def messageAgreementBot(identity: Identity2): Route =
    handleExceptions(messageExceptionHandler) {
      path("orgs" / Segment / "agbots" / Segment / "msgs" / Segment) {
        (organization,
         agreementBot,
         rawMessage) =>
          val message: Int = rawMessage.toInt // Throws NumberFormatException
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
                  getMessage(agreementBot, identity, message, organization, resource)
              }
            } ~
            delete {
              exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
                _ =>
                  deleteMessage(agreementBot, identity, message, organization, resource)
              }
            }
          
          onComplete(cacheCallback) {
            case Failure(_) => routeMethods()
            case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
          }
      }
    }
}
