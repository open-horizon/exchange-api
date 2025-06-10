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
import org.openhorizon.exchangeapi.ExchangeApiApp.myUserPassAuthenticator
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthRoles, AuthenticationSupport, IUser, Identity, Identity2, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}
import slick.lifted.{CompiledStreamingExecutable, MappedProjection}

import java.sql.Timestamp
import java.util.UUID

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
    "org1/user1": {
      "admin": false,
      "email": "user1@email1.com",
      "hubAdmin": false,
      "lastUpdated": "2025-05-13T02:34:09.357160Z[UTC]",
      "password": "***************",
      "updatedBy": "org1/user1"
    },
    "org2/user2": {
      "admin": true,
      "email": "",
      "hubAdmin": false
      "lastUpdated": "",
      "password": "***************",
      "updatedBy": ""
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
  def getUsers(@Parameter(hidden = true) identity: Identity2,
               @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"GET /orgs/$organization/users - By ${identity.resource}:${identity.role}")
      
      val getUsers: CompiledStreamingExecutable[Query[(MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, Option[String], UUID, String)], Rep[Option[(Rep[String], Rep[UUID], Rep[String])]]), (UserRow, Option[(String, UUID, String)]), Seq], Seq[(UserRow, Option[(String, UUID, String)])], (UserRow, Option[(String, UUID, String)])] =
        for {
          users <-
            Compiled(UsersTQ.filter(user => (user.organization === organization))
                            .filterIf(identity.isStandardUser)(_.user === identity.identifier.get) // Users can see themselves
                            .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root")) // Organization Admins can see other Org Admins and Users in their Organization. They cannot see Hub Admins, and they cannot see Root.
                            .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root")) // Hub Admins can see other Hub Admins and Organization Admins system-wide. They cannot see Root, and they cannot see normal users.
                            .filter(_.password.isDefined)
                            .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                            .on(_.modifiedBy === _._2)
                            .map(user =>
                                  ((user._1.createdAt,
                                   user._1.email,
                                   user._1.identityProvider,
                                   user._1.isHubAdmin,
                                   user._1.isOrgAdmin,
                                   user._1.modifiedAt,
                                   user._1.modifiedBy,
                                   user._1.organization,
                                   Option(StrConstants.hiddenPw), // DO NOT grab and return credentials.
                                   user._1.user,
                                   user._1.username), user._2)) // Because of the outer-join we cannot touch the content of these values to combine them ((organization, username) => (organization/username)).
                            .union(UsersTQ.filter(user => (user.organization === organization))  // Have to retrieve the Some and None values separately to substitute the Some values.
                                          .filterIf(identity.isStandardUser)(_.user === identity.identifier.get)
                                          .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root"))
                                          .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root"))
                                          .filter(_.password.isEmpty)
                                          .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                          .on(_.modifiedBy === _._2)
                                          .map(user =>
                                                ((user._1.createdAt,
                                                 user._1.email,
                                                 user._1.identityProvider,
                                                 user._1.isHubAdmin,
                                                 user._1.isOrgAdmin,
                                                 user._1.modifiedAt,
                                                 user._1.modifiedBy,
                                                 user._1.organization,
                                                 None,
                                                 user._1.user,
                                                 user._1.username), user._2)))
                            .sortBy(users => (users._1._8.asc, users._1._10)))
        } yield users.map(user => (user._1.mapTo[UserRow], user._2))
        
      complete({
        db.run(getUsers.result.transactionally.asTry).map {
          case Success(result) =>
            logger.debug(s"GET /orgs/$organization/users result size: " + result.size)
            if (result.isEmpty)
              (StatusCodes.NotFound, GetUsersResponse(Map.empty[String, User], 0))
            else {
              val userMap: Map[String, User] =
                result.map(result => s"${result._1.organization}/${result._1.username}" -> new User(result)).toMap // Ugly mapping, TODO: redesign response body
                
              (StatusCodes.OK, GetUsersResponse(userMap, 0))
            }
          case Failure(t: ClassNotFoundException) =>
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", organization)))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.added", t.toString))
          case Failure(t) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
        }
      })
      
      /*complete({
        logger.debug(s"GET /orgs/$organization/users identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
        
        val query =
          if (identity.isHubAdmin && !identity.isSuperUser)
            UsersTQ.getAllAdmins(organization)
          else
            UsersTQ.getAllUsers(organization)
            
        db.run(query.result).map({ list =>
          logger.debug(s"GET /orgs/$organization/users result size: ${list.size}")
          
          val users: Map[String, User] = list.map(e => e.username -> User()).toMap
          val code: StatusCode =
            if (users.nonEmpty)
              StatusCodes.OK
          else
              StatusCodes.NotFound
          
          (code, GetUsersResponse(users, 0))
        })
      }) // end of complete*/
    }
  
  def users(identity: Identity2): Route =
    path("orgs" / Segment / "users") {
      organization =>
        get {
          exchAuth(TUser(OrgAndId(organization, "#").toString), Access.READ, validIdentity = identity) {
            _ =>
              getUsers(identity, organization)
          }
        }
    }
}
