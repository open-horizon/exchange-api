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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.GetNodeMsgsResponse
import org.openhorizon.exchangeapi.table.node.message.{NodeMsg, NodeMsgsTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
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
  def getMessageNode(message: String,
                     resource: String): Route =
    complete({
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
            else
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
          case Failure(t) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
        })
    })
  
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
  def deleteMessageNode(message: String,
                        node: String,
                        resource: String): Route =
    complete({
      try {
        val msgId: Int = message.toInt   // this can throw an exception, that's why this whole section is in a try/catch
        db.run(NodeMsgsTQ.getMsg(resource,msgId).delete.asTry).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + node + "/msgs/" + msgId + " updated in changes table: " + v)
            (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.msg.not.deleted", msgId, resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.deleted", msgId, resource, t.toString)))
        })
      } catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
    })
  
  val messageNode: Route =
    path("orgs" / Segment / "nodes" / Segment / "msgs" / Segment) {
      (organization,
       node,
       message) =>
        val resource: String = OrgAndId(organization, node).toString
        
        delete {
          exchAuth(TNode(resource), Access.WRITE) {
            _ =>
              deleteMessageNode(message, node, resource)
          }
        } ~
        get {
          exchAuth(TNode(resource),Access.READ) {
            _ =>
              getMessageNode(message, resource)
          }
        }
    }
}
