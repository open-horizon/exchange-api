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
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, OrgAndId, Password, TUser}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

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
  def postChangePassword(@Parameter(hidden = true) organization: String,
                         @Parameter(hidden = true) compositeId: String,
                         @Parameter(hidden = true) username: String): Route =
    entity(as[ChangePwRequest]) {
      reqBody =>
        logger.debug(s"Doing POST /orgs/$organization/users/$username")
        
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            val hashedPw: String = Password.hash(reqBody.newPassword)
            val action = reqBody.getDbUpdate(compositeId, organization, hashedPw)
            db.run(action.transactionally.asTry).map({
              case Success(n) =>
                logger.debug("POST /orgs/" + organization + "/users/" + username + "/changepw result: " + n)
                if (n.asInstanceOf[Int] > 0) {
                  AuthCache.putUser(compositeId, hashedPw, reqBody.newPassword)
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("password.updated.successfully")))
                }
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.password.not.updated", compositeId, t.toString))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.password.not.updated", compositeId, t.toString)))
            })
          })
        }
    }
  
  val changePassword: Route =
    path("orgs" / Segment / "users" / Segment / "changepw") {
      (orgid, username) =>
        val resource: String = OrgAndId(orgid, username).toString
        
        post {
          exchAuth(TUser(resource), Access.WRITE) {
            _ =>
              postChangePassword(orgid, resource, username)
          }
        }
    }
}
