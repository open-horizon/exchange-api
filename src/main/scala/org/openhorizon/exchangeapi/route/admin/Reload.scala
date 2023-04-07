package org.openhorizon.exchangeapi.route.admin

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangeApi, ExchangeApiTables, ExchangePosgtresErrorHandling, HttpCode, Password, Role, TAction}
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{OPTIONS, POST, Path}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthenticationSupport, ExchConfig, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import java.util.Properties
import scala.concurrent.ExecutionContext
import scala.util._

@Path("/v1/admin")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait Reload extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /admin/reload ===============================
  @POST
  @Path("reload")
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
  private val postReload: Route =
    post {
      logger.debug("Doing POST /admin/reload")
      complete({
        ExchConfig.reload()
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("reload.successful")))
      }) // end of complete
    }
  
  
  val reload: Route =
    pathPrefix("admin" / "reload") {
      exchAuth(TAction(), Access.ADMIN) {
        _ =>
          postReload
      }
    }
}
