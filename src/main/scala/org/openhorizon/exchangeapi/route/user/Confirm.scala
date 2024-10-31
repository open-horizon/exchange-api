package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, OrgAndId, TUser}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/users/{username}/confirm")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user")
trait Confirm extends JacksonSupport with AuthenticationSupport  {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /orgs/{organization}/users/{username}/confirm =======================
  @POST
  @Operation(summary = "Confirms if this username/password is valid", description = "Confirms whether or not this username exists and has the specified password. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "post ok"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def postConfirm(@Parameter(hidden = true) organization: String,
                  @Parameter(hidden = true) username: String): Route =
    {
      logger.debug(s"Doing POST /orgs/$organization/users/$username/confirm")
      
      complete({
        // if we get here, the user/pw has been confirmed
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("confirmation.successful")))
      })
    }
  
  val confirm: Route =
    path("orgs" / Segment / "users" / Segment / "confirm") {
      (organization, username) =>
        val resource: String = OrgAndId(organization, username).toString
        
        post {
          exchAuth(TUser(resource), Access.READ) {
            _ =>
              postConfirm(organization, username)
          }
        }
    }
}
