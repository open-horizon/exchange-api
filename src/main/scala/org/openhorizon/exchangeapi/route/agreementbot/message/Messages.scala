package org.openhorizon.exchangeapi.route.agreementbot.message

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotMsgsResponse, PostAgbotsMsgsRequest}
import org.openhorizon.exchangeapi.table.agreementbot.message.{AgbotMsg, AgbotMsgRow, AgbotMsgsTQ}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/msgs")
trait Messages extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/msgs =========================================
  @GET
  @Operation(summary = "Returns all Messages sent to this Agreement Bot (AgBot).",
             description = "They will be returned in the order they were sent. All Messages that have been sent to this AgBot will be returned, unless the AgBot has deleted some, or some are past their TTL. Can be run by a User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "maxmsgs", in = ParameterIn.QUERY, required = false, description = "Maximum number of Messages returned. If this is less than the number of Messages available, the oldest Messages are returned. Defaults to unlimited.")),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema =
                                                                     new Schema(implementation = classOf[GetAgbotMsgsResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def getMessages(@Parameter(hidden = true) agreementBot: String,
                  @Parameter(hidden = true) identity: Identity2,
                  @Parameter(hidden = true) organization: String,
                  @Parameter(hidden = true) resource: String): Route = {
    parameter("maxmsgs".as[Int].?) {
      maxMsgs =>
        Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/msgs?maxmsgs=${maxMsgs.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        complete({
          // Set the query, including maxmsgs
          var query = AgbotMsgsTQ.getMsgs(resource).sortBy(_.msgId)
          if (0 < maxMsgs.getOrElse(0))
            query = query.take(maxMsgs.get) // Get the msgs for this agbot
          db.run(query.result)
            .map({
              list =>
                logger.debug("GET /orgs/" + organization + "/agbots/" + agreementBot + "/msgs result size: " + list.size)
                //logger.debug("GET /orgs/"+orgid+"/agbots/"+id+"/msgs result: "+list.toString)
                val msgs: List[AgbotMsg] = list.map(_.toAgbotMsg).toList
                val code: StatusCode =
                  if (msgs.nonEmpty)
                    StatusCodes.OK
                  else
                    StatusCodes.NotFound
                (code, GetAgbotMsgsResponse(msgs, 0))
            })
        })
    }
  }
  
  // ========== POST /orgs/{organization}/agbots/{agreementbot}/msgs ========================================
  @POST
  @Operation(summary = "Sends a Message from a Node to an Agreement Bot (AgBot)",
             description = "The Node must first sign the Message (with its private key) and then encrypt the msg (with the AgBot's public key). Can be run by any Node.",
             parameters =
               Array(new Parameter(name = "organization",
                                   in = ParameterIn.PATH,
                                   description = "Organization identifier"),
                     new Parameter(name = "agreementbot",
                                   in = ParameterIn.PATH,
                                   description = "Agreement Bot identifier")),
             requestBody =
               new RequestBody(content =
                 Array(new Content(examples =
                   Array(new ExampleObject(value = """{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",
  "ttl": 86400
}
""")),
                                   mediaType = "application/json",
                                   schema =
                                     new Schema(implementation = classOf[PostAgbotsMsgsRequest]))),
                               required = true),
             responses =
               Array(new responses.ApiResponse(responseCode = "201",
                                               description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401",
                                               description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403",
                                               description = "access denied"),
                     new responses.ApiResponse(responseCode = "404",
                                               description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/message")
  def postMessages(@Parameter(hidden = true) agreementBot: String,
                   @Parameter(hidden = true) identity: Identity2,
                   @Parameter(hidden = true) organization: String,
                   @Parameter(hidden = true) resource: String): Route =
    entity (as[PostAgbotsMsgsRequest]) {
      reqBody =>
        Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        
          // val agbotId: String = identity.resource      //someday: handle the case where the acls allow users to send msgs
          // var msgNum = ""
          val maxMessagesInMailbox: Int = Configuration.getConfig.getInt("api.limits.maxMessagesInMailbox")
          
          // This is a high performance, high through-put, concurrent system. Unification of Timestamps is a requirement.
          val timestamp: Instant = ApiTime.nowUTCTimestamp
          val timestampString: String = timestamp.toString
          
          val createAgbotMessage =
            for {
              // Make additional room if possible.
              numMsgsDeleted <-
                Compiled(AgbotMsgsTQ.filter(_.agbotId === resource)
                                    .filter(_.timeExpires < (timestamp.minusMillis(500).toString)))
                  .delete
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Expired messages deleted:   ${numMsgsDeleted}") }
              
              numMsgsForAgbot <-
                if (maxMessagesInMailbox == 0)
                  DBIO.successful(-1)
                else
                  Compiled(AgbotMsgsTQ.filter(_.agbotId === resource)
                                      .length)
                    .result
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Mailbox capacity:           ${if (numMsgsForAgbot == -1) "-" else numMsgsForAgbot}:${if (maxMessagesInMailbox == 0) "infinite" else maxMessagesInMailbox}") }
              
              _ <-
                if (maxMessagesInMailbox == 0)
                  DBIO.successful(())
                else if (numMsgsForAgbot < maxMessagesInMailbox)
                  DBIO.successful(())
                else
                  throw new ArrayIndexOutOfBoundsException()
              
              nodePublicKey <-
                Compiled(NodesTQ.filter(_.id === identity.resource)
                                .take(1)
                                .map(_.publicKey))
                  .result
              
              _ <-
                if (nodePublicKey.size == 1 &&
                    nodePublicKey.head.nonEmpty)
                  DBIO.successful(())
                else
                  throw new ClassNotFoundException()
              
              createdChange <-
                (ResourceChangesTQ returning ResourceChangesTQ.map(_.changeId)) +=
                  ResourceChangeRow(category    = ResChangeCategory.AGBOT.toString,
                                    changeId    = 0L,
                                    lastUpdated = timestamp,
                                    id          = agreementBot,
                                    operation   = ResChangeOperation.CREATED.toString,
                                    orgId       = organization,
                                    public      = false.toString,
                                    resource    = ResChangeResource.AGBOTMSGS.toString)
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created change identifier:  ${createdChange}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created change:             ${timestamp}") }
              
              timeExpires = timestamp.plusSeconds(reqBody.ttl.toLong).toString
              
              createdMsgForNode <-
                (AgbotMsgsTQ returning AgbotMsgsTQ.map(_.msgId)) +=
                  AgbotMsgRow(agbotId     = resource,
                              message     = reqBody.message,
                              msgId       = 0,
                              nodeId      = identity.resource,
                              nodePubKey  = nodePublicKey.head,
                              timeExpires = timeExpires,
                              timeSent    = timestampString)
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message identifier: ${createdMsgForNode}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message:            ${timestampString}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message expires:    ${timeExpires}") }
            } yield (createdMsgForNode)
        
          complete {
            implicit val formats: Formats = DefaultFormats
            db.run(createAgbotMessage.transactionally.asTry)
              .map {
                case Success(msgNum) =>
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.message.inserted", msgNum)))
                case Failure(exception: DBProcessingError) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(exception.toComplete)}") }
                  exception.toComplete
                case Failure(exception: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isKeyNotFoundError(exception)) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.message.agbotid.not.found", resource, exception.getMessage))))}") }
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agbot.message.agbotid.not.found", resource, exception.getMessage)))
                  }
                  else {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("agbot.message.not.inserted", resource, exception.toString)))}") }
                    ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("agbot.message.not.inserted", resource, exception.toString))
                  }
                case Failure(exception: ClassNotFoundException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadRequest, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.agbot.not.found", identity.resource))))}") }
                  (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.agbot.not.found", identity.resource)))
                case Failure(exception: ArrayIndexOutOfBoundsException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadGateway, Serialization.write(ApiResponse(ApiRespType.BAD_GW, ExchMsg.translate("node.mailbox.full", resource, maxMessagesInMailbox))))}") }
                  (StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, ExchMsg.translate("node.mailbox.full", resource, maxMessagesInMailbox)))
                case Failure(exception) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.inserted", resource, exception.toString))))}") }
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.message.not.inserted", resource, exception.toString)))
              }
          }
    }
  
  
  def messagesAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / "msgs") {
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
                getMessages(agreementBot, identity, organization, resource)
            }
          } ~
          post {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.SEND_MSG_TO_AGBOT, validIdentity = identity) {
              _ =>
                postMessages(agreementBot, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
  
}
