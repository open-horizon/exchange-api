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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
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
  def postHeartbeatNode(node: String,
                        organization: String,
                        resource:String): Route =
    {
      logger.debug(s"Doing POST /orgs/$organization/users/$node/heartbeat")
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
  
  val heartbeatNode: Route =
    path("orgs" / Segment / "nodes" / Segment / "heartbeat") {
      (organization,
       node) =>
        val resource: String = OrgAndId(organization, node).toString
        
        post {
          exchAuth(TNode(resource),Access.WRITE) {
            _ =>
              postHeartbeatNode(node, organization, resource)
          }
        }
    }
}
