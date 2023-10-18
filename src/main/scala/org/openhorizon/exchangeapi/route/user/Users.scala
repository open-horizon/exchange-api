/** Services routes for all of the /users api methods. */
package org.openhorizon.exchangeapi.route.user

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, IUser, Identity, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}

//import org.openhorizon.exchangeapi.AuthenticationSupport._
import org.json4s._
import org.openhorizon.exchangeapi.auth.BadInputException
import org.openhorizon.exchangeapi.table._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
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
      "updatedBy": "string"
    },
    "orgid/username": {
      "password": "string",
      "admin": false,
      "email": "string",
      "lastUpdated": "string",
      "updatedBy": "string"
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
  def getUsers(identity: Identity,
               organization: String): Route =
    {
      logger.debug(s"Doing GET /orgs/$organization/users")
      
      complete({
        logger.debug(s"GET /orgs/$organization/users identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
        
        val query =
          if (identity.isHubAdmin && !identity.isSuperUser)
            UsersTQ.getAllAdmins(organization)
          else
            UsersTQ.getAllUsers(organization)
            
        db.run(query.result).map({ list =>
          logger.debug(s"GET /orgs/$organization/users result size: ${list.size}")
          
          val users: Map[String, User] = list.map(e => e.username -> User(if (identity.isSuperUser || identity.isHubAdmin) e.hashedPw else StrConstants.hiddenPw, e.admin, e.hubAdmin, e.email, e.lastUpdated, e.updatedBy)).toMap
          val code: StatusCode =
            if (users.nonEmpty)
              StatusCodes.OK
          else
              StatusCodes.NotFound
          
          (code, GetUsersResponse(users, 0))
        })
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
