package org.openhorizon.exchangeapi.route.node.message

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.GetNodeMsgsResponse
import org.openhorizon.exchangeapi.table.node.message.{NodeMsg, NodeMsgsTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/nodes/{node}/msgs/{msgid}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
trait Message extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /orgs/{organization}/nodes/{node}/msgs/{msgid} ================================ */
  @GET
  @Operation(description = "Returns A specific message that has been sent to this node. Deleted/post-TTL (Time To Live) messages will not be returned. Can be run by a user or the node.",
             parameters = Array(new Parameter(description = "ID of the node.",
                                              in = ParameterIn.PATH,
                                              name = "node",
                                              required = true),
                                new Parameter(description = "Specific node message.",
                                              in = ParameterIn.PATH,
                                              name = "msgid",
                                              required = true),
                                new Parameter(description = "Organization id.",
                                              in = ParameterIn.PATH,
                                              name = "organization",
                                              required = true)),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetNodeMsgsResponse]))),
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
             summary = "Returns A specific message that has been sent to this node.")
  def getMessageNode(@Parameter(hidden = true) identity: Identity2,
                     @Parameter(hidden = true) message: String,
                     @Parameter(hidden = true) node: String,
                     @Parameter(hidden = true) organization: String,
                     @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      implicit val formats: Formats = DefaultFormats
      db.run(
             NodeMsgsTQ.getMsg(nodeId = resource,
                               msgId = message.toInt)
                       .result
                       .map(
                         result =>
                           GetNodeMsgsResponse(lastIndex = 0,
                                               messages =
                                                 result.map(
                                                   message =>
                                                     NodeMsg(agbotId = message.agbotId,
                                                             agbotPubKey = message.agbotPubKey,
                                                             message = message.message,
                                                             msgId = message.msgId,
                                                             timeExpires = message.timeExpires,
                                                             timeSent = message.timeSent)).toList))
                       .asTry)
        .map({
          case Success(message) =>
            if(message.messages.nonEmpty)
              (HttpCode.OK, message)
            else {
              Future { logger.debug(s"GET /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - message.messages.nonEmpty:${message.messages.nonEmpty} - ${(HttpCode.NOT_FOUND, Serialization.write(ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found"))))}") }
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
            }
          case Failure(exception) =>
            Future { logger.debug(s"GET /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.BAD_INPUT, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage))))}") }
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", exception.getMessage)))
        })
    })
  }
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/msgs/{msgid} ===============================
  @DELETE
  @Operation(summary = "Deletes a msg of an node", description = "Deletes a message that was sent to an node. This should be done by the node after each msg is read. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "msgid", in = ParameterIn.PATH, description = "ID of the msg to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteMessageNode(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) message: String,
                        @Parameter(hidden = true) node: String,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      implicit val formats: Formats = DefaultFormats
      try {
        val msgId: Int = message.toInt   // this can throw an exception, that's why this whole section is in a try/catch
        db.run(NodeMsgsTQ.getMsg(resource,msgId).delete.asTry).map({
          case Success(v) =>
            Future { logger.debug(s"DELETE /nodes/$node/msgs/$msgId - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")} - updated in changes table: $v") }
            (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.deleted")))
          case Failure(exception: DBProcessingError) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(exception.toComplete)}") }
            exception.toComplete
          case Failure(exception: org.postgresql.util.PSQLException) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("node.msg.not.deleted", msgId, resource, exception.toString)))}") }
            ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("node.msg.not.deleted", msgId, resource, exception.toString))
          case Failure(exception) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.deleted", msgId, resource, exception.toString))))}") }
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.deleted", msgId, resource, exception.toString)))
        })
      }
      catch {
        case e: Exception =>
          Future { logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/msgs/${message} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${e.toString} - ${(HttpCode.BAD_INPUT, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))))}") }
          (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e)))
      }    // the specific exception is NumberFormatException
    })
  }
  
  def messageNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "msgs" / Segment) {
      (organization,
       node,
       message) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          delete {
            exchAuth(TNode(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteMessageNode(identity, message, node, organization, resource)
            }
          } ~
          get {
            exchAuth(TNode(resource, owningResourceIdentity),Access.READ, validIdentity = identity) {
              _ =>
                getMessageNode(identity, message, node, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
