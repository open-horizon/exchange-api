/** Services routes for all of the /users api methods. */
package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, IUser, Identity, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeysTQ,ApiKeyMetadata}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}

//import org.openhorizon.exchangeapi.AuthenticationSupport._
import org.json4s._
import org.openhorizon.exchangeapi.auth.BadInputException
import org.openhorizon.exchangeapi.table._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.concurrent.{ExecutionContext,Future}
import scala.util._

@Path("/v1/orgs/{organization}/users")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user")
trait Users extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  
  // =========== GET /orgs/{organization}/users ===========================================
  @GET
  @Operation(summary = "Returns all users", description = "Returns all users. Can only be run by the root user, org admins, and hub admins.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "users": {
    "orgid/username": {
      "password": "string",
      "admin": false,
      "email": "string",
      "lastUpdated": "string",
      "updatedBy": "string",
      "apikeys": [
        {
          "id": "string",
          "description": "string",
          "lastUpdated": "string"
        }
      ]
    },
    "orgid/username": {
      "password": "string",
      "admin": false,
      "email": "string",
      "lastUpdated": "string",
      "updatedBy": "string",
      "apikeys": [
        {
          "id": "string",
          "description": "string",
          "lastUpdated": "string"
        }
      ]
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetUsersResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getUsers(@Parameter(hidden = true) identity: Identity,
               @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"Doing GET /orgs/$organization/users")
      
      complete({
        logger.debug(s"GET /orgs/$organization/users identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
        
        val query =
          if (identity.isHubAdmin && !identity.isSuperUser)
            UsersTQ.getAllAdmins(organization)
          else
            UsersTQ.getAllUsers(organization)
            
    db.run(query.result).flatMap { list =>
      if (list.nonEmpty) {
        // fetch apikeys for each user
        Future.sequence {
          list.map { userRow =>
            db.run(ApiKeysTQ.getByUser(userRow.username).result).map { keys =>
              val keyMetadata = keys.map(row =>
                ApiKeyMetadata(
                id = row.id,
                description = row.description,
                owner = null, 
                lastUpdated = row.modifiedBy ))

              val user = org.openhorizon.exchangeapi.table.user.User(
                password = if (identity.isSuperUser || identity.isHubAdmin) userRow.hashedPw else StrConstants.hiddenPw,
                admin = userRow.admin,
                hubAdmin = userRow.hubAdmin,
                email = userRow.email,
                lastUpdated = userRow.lastUpdated,
                updatedBy = userRow.updatedBy,
                apikeys = Some(keyMetadata)
              )

              userRow.username -> user
            }
          }
        }.map { usersMap =>
          (StatusCodes.OK, GetUsersResponse(usersMap.toMap, 0))
        }
      } else {
        Future.successful((StatusCodes.NotFound, GetUsersResponse(Map.empty, 0)))
      }
    }
      }) // end of complete
    }
  
  def users: Route =
    path("orgs" / Segment / "users") {
      organization =>
        get {
          exchAuth(TUser(OrgAndId(organization, "#").toString), Access.READ) {
            identity =>
              getUsers(identity, organization)
          }
        }
    }
}
