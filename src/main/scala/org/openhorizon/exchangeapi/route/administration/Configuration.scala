package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, TAction}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import java.util.Properties
import scala.concurrent.ExecutionContext

trait Configuration extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /** set 1 or more variables in the in-memory config (does not affect all instances in multi-node mode).
    * Intentionally not put swagger, because only used by automated tests. */
  def putConfiguration: Route =
    entity (as[AdminConfigRequest]) {
      reqBody =>
        logger.debug(s"Doing POST /admin/config")
        complete({
          val props = new Properties()
          props.setProperty(reqBody.varPath, reqBody.value)
          Configuration.reload(properties = props)
          (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("config.value.set")))
        }) // end of complete
    }
  
  
  val configuration: Route =
    path("admin" / "config") {
      put {
        exchAuth(TAction(), Access.ADMIN) {
          _ =>
            putConfiguration
        }
      }
    }
}
