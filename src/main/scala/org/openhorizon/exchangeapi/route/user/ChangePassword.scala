package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, Identity2, OrgAndId, Password, TUser}
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceIdentity, cacheResourceOwnership}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}
import scalacache.modes.scalaFuture._

import java.util.UUID

@Path("/v1/orgs/{organization}/users/{username}/changepw")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user")
trait ChangePassword extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /orgs/{organization}/users/{username}/changepw ======================
  @POST
  @Operation(
    summary = "Changes the user's password",
    description = "Changes the user's password. Only the user itself, root, or a user with admin privilege can update an existing user's password.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "username",
        in = ParameterIn.PATH,
        description = "Username of the user."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "newPassword": "abc"
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[ChangePwRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "password updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  def postChangePassword(@Parameter(hidden = true) identity: Identity2,
                         @Parameter(hidden = true) organization: String,
                         @Parameter(hidden = true) resource: String,
                         @Parameter(hidden = true) username: String): Route =
    entity(as[ChangePwRequest]) {
      reqBody =>
        Future { logger.debug(s"POST /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}") }
        
        validateWithMsg(reqBody.getAnyProblem) {
          val timestamp: java.sql.Timestamp = ApiTime.nowUTCTimestamp
                      val isOAuthEnabled = Configuration.getConfig.getBoolean("api.oauth.enabled")
          
          val action =
                Compiled(UsersTQ.filter(user => (user.organization === organization &&
                                                 user.username === username))
                                .filterIf(identity.isUser && !identity.isSuperUser)(users => (users.organization ++ "/" ++ users.username) =!= "root/root")
                                .filterIf(identity.isStandardUser)(users => users.user === identity.identifier)
                                .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin)
                                .filterIf(identity.isHubAdmin)(user => (user.isHubAdmin || user.isOrgAdmin))
                                .map(user =>
                                      (user.modifiedAt,
                                       user.modifiedBy,
                                       user.password)))
                                .update(timestamp,
                                        identity.identifier,
                                        Option(Password.hash(reqBody.newPassword))) // Grab this last second.
 
            val checkExternalUserQuery = Compiled(
              UsersTQ.filter(user => user.organization === organization && user.username === username)
                    .filter(_.identityProvider =!= "Open Horizon")
                    .take(1)
                    .length
            )
            
            val checkUserAndUpdate: DBIOAction[Int, NoStream, Effect.Read with Effect.Write] =
              if (isOAuthEnabled) {
                for {
                  externalUserCount <- checkExternalUserQuery.result
                  
                  _ <- if (externalUserCount > 0) {
                    DBIO.failed(new MethodNotAllowedException(ExchMsg.translate("password.disabled.oauth.user")))
                  } else {
                    DBIO.successful(())
                  }
                  
                  numUsersModified <- action
                } yield numUsersModified
              } else {
                action
              }
          
          complete {
            db.run(checkUserAndUpdate.transactionally.asTry).map {
              case Success(numUsersModified) =>
                Future { logger.debug("POST /orgs/" + organization + "/users/" + username + "/changepw result: " + numUsersModified)
                }
                if (0 < numUsersModified) {
                  Future {
                    if (resource == identity.resource)
                      cacheResourceIdentity.put(resource)(value = (identity, Password.hash(reqBody.newPassword)), ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds))
                    else
                      cacheResourceIdentity.remove(resource)
                  }
                  
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("password.updated.successfully")))
                }
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
                case Failure(t: MethodNotAllowedException) =>
                  (HttpCode.NOT_ALLOWED, ApiResponse(ApiRespType.METHOD_NOT_ALLOWED, ExchMsg.translate("password.disabled.oauth.user")))
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.password.not.updated", resource, t.toString))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.password.not.updated", resource, t.toString)))
            }
          }
        }
    }

  def changePassword(identity: Identity2): Route =
    path("orgs" / Segment / "users" / Segment / "changepw") {
      (organization, username) =>
        val resource: String = OrgAndId(organization, username).toString
        val resource_type: String = "user"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, username, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(resource_identity: Option[UUID]): Route =
          post {
            exchAuth(TUser(resource, resource_identity), Access.WRITE, validIdentity = identity) {
              _ =>
                postChangePassword(identity, organization, resource, username)
            }
          }
          
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods(resource_identity = None)
          case Success((resource_identity, _)) => routeMethods(resource_identity = Option(resource_identity))
        }
    }
}
