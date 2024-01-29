package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import jakarta.ws.rs.Path
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, TAction}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext


// @Path("/v1/admin")
// @io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait ClearAuthCache extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== POST /admin/clearAuthCaches ===============================
  def postClearAuthCache: Route = {
    logger.debug("Doing POST /admin/clearauthcaches")
    complete({ // todo: ensure other client requests are not updating the cache at the same time
    
      AuthCache.clearAllCaches(includingIbmAuth = true)
      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("cache.cleared")))
    })
  }
  
  
  val clearAuthCache: Route =
    path("admin" / "clearauthcaches") {
      post {
        exchAuth(TAction(), Access.ADMIN) {
          _ =>
            postClearAuthCache
        }
      }
    }
}
