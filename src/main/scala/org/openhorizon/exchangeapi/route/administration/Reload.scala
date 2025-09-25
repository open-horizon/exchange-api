package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApi
import com.github.pjfanning.pekkohttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{OPTIONS, POST, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, Identity2, Password, Role, TAction}
import org.openhorizon.exchangeapi.table.ExchangeApiTables
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import java.util.Properties
import scala.concurrent.ExecutionContext
import scala.util._

@Path("/v1/admin/reload")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait Reload extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /admin/reload ===============================
  @POST
  @Operation(description = """Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user.""",
             responses =
               Array(
                 new responses.ApiResponse(content =
                                             Array(new Content(mediaType = "application/json",
                                                               schema = new Schema(implementation = classOf[ApiResponse]))),
                                           description = "response body",
                                           responseCode = "201"),
                 new responses.ApiResponse(description = "invalid credentials",
                                           responseCode = "401"),
                 new responses.ApiResponse(description = "access denied",
                                           responseCode = "403")),
             summary = "Tells the exchange reread its config file")
  //val postReload: Route = (path("admin" / "reload") & post) {
  def postReload(@Parameter(hidden = true) identity: Identity2): Route = {
    logger.debug(s"POST /admin/reload - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        Configuration.reload()
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("reload.successful")))
      }) // end of complete
  }
  
  
  def reload(identity: Identity2): Route =
    path("admin" / "reload") {
      post {
        exchAuth(TAction(), Access.ADMIN, validIdentity = identity) {
          _ =>
            postReload(identity)
        }
      }
    }
}
