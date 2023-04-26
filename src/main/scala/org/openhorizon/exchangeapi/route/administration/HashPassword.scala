package org.openhorizon.exchangeapi.route.administration

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.{Access, ApiResponse, AuthenticationSupport, HttpCode, Password, TAction}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, responses}
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{POST, Path}
import org.openhorizon.exchangeapi.{Access, AuthenticationSupport, Password}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1/admin/hashpw")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait HashPassword extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /admin/hashpw ===============================
  @POST
  @Operation(summary = "Returns a bcrypted hash of a password",
             description = "Takes the password specified in the request body, bcrypts it with a random salt, and returns the result. This can be useful if you want to specify root's hash pw in the config file instead of the clear pw.",
             requestBody =
               new RequestBody(
                 content = Array(new Content(
                   examples = Array(new ExampleObject(
                     value = """{
  "password": "pw to bcrypt"
}""")),
                   mediaType = "application/json",
                   schema = new Schema(implementation = classOf[AdminHashpwRequest]))),
                 required = true),
             responses =
               Array(new responses.ApiResponse(
                 responseCode = "201",
                 description = "response body",
                 content = Array(new Content(
                   mediaType = "application/json",
                   schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401",
                                               description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403",
                                               description = "access denied")))
  def postHashPW: Route = {
    entity(as[AdminHashpwRequest]) {
      reqBody =>
        logger.debug("Doing POST /admin/hashpw")
        complete({
          (HttpCode.POST_OK, AdminHashpwResponse(Password.hash(reqBody.password)))
        })
      }
  }
  
  
  val hashPW: Route =
    path("admin" / "hashpw") {
      post {
        exchAuth(TAction(), Access.UTILITIES) {
          _ =>
            postHashPW
        }
      }
    }
}
