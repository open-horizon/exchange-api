package org.openhorizon.exchangeapi.route.node

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/nodes/{node}/heartbeat")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node")
trait Heartbeat extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== POST /orgs/{organization}/nodes/{node}/heartbeat ===============================
  @POST
  @Operation(summary = "Tells the exchange this node is still operating", description = "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def postHeartbeatNode(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) node: String,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) resource:String): Route =
    {
      logger.debug(s"POST /orgs/$organization/users/$node/heartbeat - By ${identity.resource}:${identity.role}")
      complete({
        db.run(NodesTQ.getLastHeartbeat(resource).update(Some(ApiTime.nowUTC)).asTry).map({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"POST /orgs/$organization/users/$node/heartbeat result: $v")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.updated")))
            } else {
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource)))
            }
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.updated", resource, t.toString))
          case Failure(t) => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.updated", resource, t.toString)))
        })
      })
    }
  
  def heartbeatNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "heartbeat") {
      (organization,
       node) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        
        val i: Option[UUID] =
          try
            Option(Await.result(cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
              ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, something = resource_type)
            }, 15.seconds)._1)
          catch {
            case _: Throwable => None
          }
        
        post {
          exchAuth(TNode(resource, i),Access.WRITE, validIdentity = identity) {
            _ =>
              postHeartbeatNode(identity, node, organization, resource)
          }
        }
    }
}
