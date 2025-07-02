package org.openhorizon.exchangeapi.route.administration.dropdatabase

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, Role, TAction}
import org.openhorizon.exchangeapi.route.administration.AdminDropdbTokenResponse
import org.openhorizon.exchangeapi.utility.HttpCode
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1/admin/dropdb/token")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait Token extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== GET /admin/dropdb/token ===============================
  @GET
  @Operation(summary = "Gets a 1-time token for deleting the DB",
             description = """Returns a timed token that can be given to POST /admin/dropdb. The token is good for 10 minutes. Since dropping the DB tables deletes all of their data, this is a way of confirming you really want to do it. This can only be run as root.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[AdminDropdbTokenResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def getToken(@Parameter(hidden = true) identity:Identity2): Route =
    get {
      logger.debug(s"GET /admin/dropdb/token - By ${identity.resource}:${identity.role}")
      complete({
        (HttpCode.OK, AdminDropdbTokenResponse(createToken(Role.superUser)))
      }) // end of complete
    }
  
  
  def token(identity: Identity2): Route =
    path("admin" / "dropdb" / "token") {
      get {
        exchAuth(TAction(), Access.ADMIN, validIdentity = identity) {
          _ =>
            getToken(identity)
        }
      }
    }
}
