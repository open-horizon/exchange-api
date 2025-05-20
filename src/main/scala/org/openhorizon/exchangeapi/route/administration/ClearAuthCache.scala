package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.Parameter
import jakarta.ws.rs.Path
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, Identity2, TAction}
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
  def postClearAuthCache(@Parameter(hidden = true) identity: Identity2): Route = {
    logger.debug(s"POST /admin/clearauthcaches  - By ${identity.resource}:${identity.role}")
    complete({ // todo: ensure other client requests are not updating the cache at the same time
    
      AuthCache.clearAllCaches(includingIbmAuth = true)
      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("cache.cleared")))
    })
  }
  
  
  def clearAuthCache(identity: Identity2): Route =
    path("admin" / "clearauthcaches") {
      post {
        exchAuth(TAction(), Access.ADMIN, validIdentity = identity) {
          _ =>
            postClearAuthCache(identity)
        }
      }
    }
}
