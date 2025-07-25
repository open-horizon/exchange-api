package org.openhorizon.exchangeapi.route.node.message

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, get, parameter, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.{GetNodeMsgsResponse, PostNodesMsgsRequest}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.message.{NodeMsg, NodeMsgRow, NodeMsgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


@Path("/v1/orgs/{organization}/nodes/{node}/msgs")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
trait Messages extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /orgs/{organization}/nodes/{node}/msgs ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all msgs sent to this node", description = "Returns all msgs that have been sent to this node. They will be returned in the order they were sent. All msgs that have been sent to this node will be returned, unless the node has deleted some, or some are past their TTL. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "maxmsgs", in = ParameterIn.QUERY, required = false, description = "Maximum number of messages returned. If this is less than the number of messages available, the oldest messages are returned. Defaults to unlimited.")
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetNodeMsgsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getMessagesNode(@Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) node: String,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String): Route =
    parameter("maxmsgs".?) {
      maxmsgsStrOpt =>
        logger.debug(s"GET /orgs/${organization}/nodes/${node}/msgs?maxmsgs=${maxmsgsStrOpt.getOrElse("None")} - By ${identity.resource}:${identity.role}")
        
        validate(Try(maxmsgsStrOpt.map(_.toInt)).isSuccess, ExchMsg.translate("invalid.int.for.name", maxmsgsStrOpt.getOrElse(""), "maxmsgs")) {
          complete({
            // Set the query, including maxmsgs
            var maxIntOpt = maxmsgsStrOpt.map(_.toInt)
            var query = NodeMsgsTQ.getMsgs(resource).sortBy(_.msgId)
            if (maxIntOpt.getOrElse(0) > 0) query = query.take(maxIntOpt.get)
            // Get the msgs for this agbot
            db.run(query.result).map({ list =>
              logger.debug("GET /orgs/"+organization+"/nodes/"+node+"/msgs result size: "+list.size)
              //logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs result: "+list.toString)
              val msgs: List[NodeMsg] = list.map(_.toNodeMsg).toList
              val code: StatusCode = if (msgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetNodeMsgsResponse(msgs, 0))
            })
          })
        }
    }
  
  // =========== POST /orgs/{organization}/nodes/{node}/msgs ===============================
  @POST
  @Operation(
    summary = "Sends a msg from an agbot to a node",
    description = "Sends a msg from an agbot to a node. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the node's public key). Can be run by any agbot.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "ID of the node to send a message to."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",
  "ttl": 86400
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodesMsgsRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(
          new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse]))
        )
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
  def postMessagesNode(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) node: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route =
    entity(as[PostNodesMsgsRequest]) {
      reqBody =>
        Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        
          // val agbotId: String = identity.resource      //someday: handle the case where the acls allow users to send msgs
          // var msgNum = ""
          val maxMessagesInMailbox: Int = Configuration.getConfig.getInt("api.limits.maxMessagesInMailbox")
          
          // This is a high performance, high through-put, concurrent system. Unification of Timestamps is a requirement.
          val timestamp: Timestamp = ApiTime.nowUTCTimestamp
          val timestampString: String =
            fixFormatting(timestamp.toInstant
                                   .atZone(ZoneId.of("UTC"))
                                   .withZoneSameInstant(ZoneId.of("UTC"))
                                   .toString)
          
          val createNodeMessage =
            for {
              // Make additional room if possible.
              numMsgsDeleted <-
                Compiled(NodeMsgsTQ.filter(_.nodeId === resource)
                                   .filter(_.timeExpires < (fixFormatting(timestamp.toInstant
                                                                                   .minusMillis(5)  // Allow a little grace for concurrent messages coming from multiple Exchange instances.
                                                                                   .atZone(ZoneId.of("UTC"))
                                                                                   .withZoneSameInstant(ZoneId.of("UTC"))
                                                                                   .toString))))
                  .delete
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Expired messages deleted:   ${numMsgsDeleted}") }
              
              numMsgsForNode <-
                if (maxMessagesInMailbox == 0)
                  DBIO.successful(-1)
                else
                  Compiled(NodeMsgsTQ.filter(_.nodeId === resource)
                                     .length)
                    .result
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Mailbox capacity:           ${if (numMsgsForNode == -1) "-" else numMsgsForNode}:${if (maxMessagesInMailbox == 0) "infinite" else maxMessagesInMailbox}") }
              
              _ <-
                if (maxMessagesInMailbox == 0)
                  DBIO.successful(())
                else if (numMsgsForNode < maxMessagesInMailbox)
                  DBIO.successful(())
                else
                  throw new ArrayIndexOutOfBoundsException()
              
              agbotPublicKey <-
                Compiled(AgbotsTQ.filter(_.id === identity.resource)
                                 .take(1)
                                 .map(_.publicKey))
                  .result
              
              _ <-
                if (agbotPublicKey.size == 1 &&
                    agbotPublicKey.head.nonEmpty)
                  DBIO.successful(())
                else
                  throw new ClassNotFoundException()
              
              createdChange <-
                (ResourceChangesTQ returning ResourceChangesTQ.map(_.changeId)) +=
                  ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                    changeId = 0L,
                                    lastUpdated = timestamp,
                                    id = node,
                                    operation = ResChangeOperation.CREATED.toString,
                                    orgId = organization,
                                    public = false.toString,
                                    resource = ResChangeResource.NODEMSGS.toString)
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created change identifier:  ${createdChange}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created change:             ${timestamp}") }
              
              timeExpires =
                fixFormatting(timestamp.toInstant
                                       .plusSeconds(reqBody.ttl.toLong)
                                       .atZone(ZoneId.of("UTC"))
                                       .withZoneSameInstant(ZoneId.of("UTC"))
                                       .toString)
              
              createdMsgForNode <-
                (NodeMsgsTQ returning NodeMsgsTQ.map(_.msgId)) +=
                  NodeMsgRow(agbotId = identity.resource,
                             agbotPubKey = agbotPublicKey.head,
                             message = reqBody.message,
                             msgId = 0,
                             nodeId = resource,
                             timeExpires = timeExpires,
                             timeSent = timestampString)
              
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message identifier: ${createdMsgForNode}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message:            ${timestampString}") }
              _ = Future { logger.debug(s"POST /orgs/${organization}/nodes/${node}/msgs - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Created message expires:    ${timeExpires}") }
            } yield (createdMsgForNode)
        
          complete {
            db.run(createNodeMessage.transactionally.asTry)
              .map {
                case Success(msgNum) =>
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.inserted", msgNum)))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isKeyNotFoundError(t)) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.msg.nodeid.not.found", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.msg.not.inserted", resource, t.toString))
                case Failure(_: ClassNotFoundException) =>
                  (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.agbot.not.found", identity.resource)))
                case Failure(_: ArrayIndexOutOfBoundsException) =>
                  (StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, ExchMsg.translate("node.mailbox.full", resource, maxMessagesInMailbox)))
                case Failure(t) =>
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.inserted", resource, t.toString)))
              }
          }
    }
  
  def messagesNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "msgs") {
      (organization,
       node) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          get {
            exchAuth(TNode(resource, owningResourceIdentity),Access.READ, validIdentity = identity) {
              _ =>
                getMessagesNode(identity, node, organization, resource)
            }
          } ~
          post {
            exchAuth(TNode(resource, owningResourceIdentity),Access.SEND_MSG_TO_NODE, validIdentity = identity) {
              _ =>
                postMessagesNode(identity, node, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
