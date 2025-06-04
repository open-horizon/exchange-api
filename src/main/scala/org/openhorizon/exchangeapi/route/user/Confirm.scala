package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TUser}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

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
  def postConfirm(@Parameter(hidden = true) identity: Identity2,
                  @Parameter(hidden = true) organization: String,
                  @Parameter(hidden = true) username: String): Route =
    {
      Future { logger.debug(s"POST /orgs/$organization/users/$username/confirm - By ${identity.resource}:${identity.role}") }
      
      complete {
        // if we get here, the user/pw has been confirmed
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("confirmation.successful")))
      }
    }
  
  def confirm(identity: Identity2): Route =
    path("orgs" / Segment / "users" / Segment / "confirm") {
      (organization, username) =>
        val resource: String = OrgAndId(organization, username).toString
        val resource_type = "user"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, username, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(resource_identity: Option[UUID]): Route =
          post {
            exchAuth(TUser(resource, resource_identity), Access.READ, validIdentity = identity) {
              _ =>
                postConfirm(identity, organization, username)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods(resource_identity = None)
          case Success((resource_identity, _)) => routeMethods(resource_identity = Option(resource_identity))
        }
    }
}
