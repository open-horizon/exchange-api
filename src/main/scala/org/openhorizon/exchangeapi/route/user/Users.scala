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
import org.apache.pekko.http.scaladsl.server.Directives.{Segment, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthRoles, AuthenticationSupport, IUser, Identity, Identity2, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeys, ApiKeyRow, ApiKeysTQ,ApiKeyMetadata}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}
import slick.lifted.{CompiledStreamingExecutable, MappedProjection}

import java.sql.Timestamp
import java.util.UUID
import scala.concurrent.Future

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
      "updatedBy": "org1/user1",
      "apikeys": [
        {
          "id": "string",
          "description": "string",
          "lastUpdated": "string"
        }
      ]
    },
    "org2/user2": {
      "admin": true,
      "email": "",
      "hubAdmin": false,
      "lastUpdated": "",
      "password": "***************",
      "updatedBy": "",
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
  def getUsers(@Parameter(hidden = true) identity: Identity2,
               @Parameter(hidden = true) organization: String): Route =
    {
      logger.debug(s"GET /orgs/$organization/users - By ${identity.resource}:${identity.role}")
      
      val getUsersWithApiKeys: CompiledStreamingExecutable[Query[(MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, Option[String], UUID, String)], Rep[Option[(Rep[String], Rep[UUID], Rep[String])]], Rep[Option[ApiKeys]]), (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow]), Seq], Seq[(UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])], (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])] =
        for {
          users <-
            Compiled((UsersTQ.filter(user => (user.organization === organization))
                            .filterIf(identity.isStandardUser)(_.user === identity.identifier.get) // Users can see themselves
                            .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root")) // Organization Admins can see other Org Admins and Users in their Organization. They cannot see Hub Admins, and they cannot see Root.
                            .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root")) // Hub Admins can see other Hub Admins and Organization Admins system-wide. They cannot see Root, and they cannot see normal users.
                            .filter(_.password.isDefined)
                            .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                            .on(_.modifiedBy === _._2)
                            .joinLeft(ApiKeysTQ)
                            .on(_._1.user === _.user)
                            .map(user =>
                                  ((user._1._1.createdAt,
                                   user._1._1.email,
                                   user._1._1.identityProvider,
                                   user._1._1.isHubAdmin,
                                   user._1._1.isOrgAdmin,
                                   user._1._1.modifiedAt,
                                   user._1._1.modifiedBy,
                                   user._1._1.organization,
                                   Option(StrConstants.hiddenPw), // DO NOT grab and return credentials.
                                   user._1._1.user,
                                   user._1._1.username), user._1._2, user._2))) // Because of the outer-join we cannot touch the content of these values to combine them ((organization, username) => (organization/username)).
                            .union(UsersTQ.filter(user => (user.organization === organization))  // Have to retrieve the Some and None values separately to substitute the Some values.
                                          .filterIf(identity.isStandardUser)(_.user === identity.identifier.get)
                                          .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root"))
                                          .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root"))
                                          .filter(_.password.isEmpty)
                                          .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                          .on(_.modifiedBy === _._2)
                                          .joinLeft(ApiKeysTQ)
                                          .on(_._1.user === _.user)
                                          .map(user =>
                                                ((user._1._1.createdAt,
                                                 user._1._1.email,
                                                 user._1._1.identityProvider,
                                                 user._1._1.isHubAdmin,
                                                 user._1._1.isOrgAdmin,
                                                 user._1._1.modifiedAt,
                                                 user._1._1.modifiedBy,
                                                 user._1._1.organization,
                                                 None,
                                                 user._1._1.user,
                                                 user._1._1.username), user._1._2, user._2)))
                            .sortBy(users => (users._1._8.asc, users._1._10)))
        } yield users.map(user => (user._1.mapTo[UserRow], user._2, user._3))
        
      complete({
            db.run(getUsersWithApiKeys.result.transactionally).map { result =>
                  // logger.debug(s"GET /orgs/$organization/users result size: " + result.size)

                  if (result.isEmpty) {
                        (StatusCodes.NotFound, GetUsersResponse(Map.empty[String, User], 0))
                  } else {
                        val groupedByUser = result.groupBy(_._1.user) // Group results by user UUID 
                        
                        val userMap: Map[String, User] = groupedByUser.map { case (userUuid, userResults) =>
                              val userResult = userResults.head  
                              val userRow = userResult._1
                              
                              val apiKeyMetadataList = userResults.filter(_._3.isDefined).map { case (_, _, Some(apiKeyRow)) =>
                                    new ApiKeyMetadata(apiKeyRow, null)
                              }.distinct

                              val user = new User((userResult._1, userResult._2), Some(apiKeyMetadataList))
                              s"${userRow.organization}/${userRow.username}" -> user
                        }.toMap   // Ugly mapping, TODO: redesign response body

                        (StatusCodes.OK, GetUsersResponse(userMap, 0))
                  }
            }.recover {
                  case t: ClassNotFoundException =>
                        (HttpCode.NOT_FOUND,
                              ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", organization)))

                  case t: org.postgresql.util.PSQLException =>
                        ExchangePosgtresErrorHandling.ioProblemError(
                              t, ExchMsg.translate("user.not.added", t.toString))

                  case t =>
                        (HttpCode.BAD_INPUT,
                              ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
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
  
// TODO: These hardcoded /apikey and /iamapikey endpoints are temporary workarounds 
// for legacy components that pass "apikey"/"iamapikey" as username in HTTP requests.
// Long-term solution: update calling components to use proper authentication instead 
// of these compatibility endpoints.
  // =========== GET /orgs/{organization}/users/apikey and /orgs/{organization}/users/apikey ================================
  @GET
  @Path("/apikey")
  @Operation(summary = "Returns current user info", description = "Returns the authenticated user's own information using apikey authentication.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          
          new Content(
            examples = Array(
              new ExampleObject(
                value =
                  """{
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
  def getUserSelfApikey(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) organization: String): Route =
    get {
      if (identity.organization != organization) {
        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.path.mismatch"))))
      } 
      else{
        logger.debug(s"GET /orgs/$organization/users/apikey - By ${identity.resource}:${identity.role}")
        
        val getUserWithApiKeys: CompiledStreamingExecutable[Query[(MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, Option[String], UUID, String)], Rep[Option[(Rep[String], Rep[UUID], Rep[String])]], Rep[Option[ApiKeys]]), (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow]), Seq], Seq[(UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])], (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])] =
          for {
            users <-
              Compiled((UsersTQ.filter(user => (user.organization === organization &&
                                              user.username === identity.username))
                              .filter(_.password.isDefined)
                              .take(1)
                              .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                              .on(_.modifiedBy === _._2)
                              .joinLeft(ApiKeysTQ)
                              .on(_._1.user === _.user)
                              .map(users =>
                                    ((users._1._1.createdAt,
                                    users._1._1.email,
                                    users._1._1.identityProvider,
                                    users._1._1.isHubAdmin,
                                    users._1._1.isOrgAdmin,
                                    users._1._1.modifiedAt,
                                    users._1._1.modifiedBy,
                                    users._1._1.organization,
                                    Option(StrConstants.hiddenPw),  // DO NOT grab and return credentials.
                                    users._1._1.user,
                                    users._1._1.username), users._1._2, users._2)))  // Because of the outer-join we cannot touch the content of these values to combine them ((organization, username) => (organization/username)).
                      ++
                      (UsersTQ.filter(user => (user.organization === organization && // Have to retrieve the Some and None values separately to substitute the Some values.
                                              user.username === identity.username))
                              .filter(_.password.isEmpty)
                              .take(1)
                              .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                              .on(_.modifiedBy === _._2)
                              .joinLeft(ApiKeysTQ)
                              .on(_._1.user === _.user)
                              .map(users =>
                                    ((users._1._1.createdAt,
                                      users._1._1.email,
                                      users._1._1.identityProvider,
                                      users._1._1.isHubAdmin,
                                      users._1._1.isOrgAdmin,
                                      users._1._1.modifiedAt,
                                      users._1._1.modifiedBy,
                                      users._1._1.organization,
                                      None,
                                      users._1._1.user,
                                      users._1._1.username), users._1._2, users._2))))
          } yield users.map(user => (user._1.mapTo[UserRow], user._2, user._3))
          
        complete({
              db.run(getUserWithApiKeys.result.transactionally).map { result =>
                    // logger.debug(s"GET /orgs/$organization/users/$username result size: " + result.size)

                    if (result.nonEmpty) {
                          val userResult = result.head
                          val userRow = userResult._1
                          val modifiedByInfo = userResult._2

                          val apiKeyMetadataList = result.filter(_._3.isDefined).map { case (_, _, Some(apiKeyRow)) =>
                                new ApiKeyMetadata(apiKeyRow, null)
                          }.distinct

                          val user = new User((userRow, modifiedByInfo), Some(apiKeyMetadataList))
                          val userMap: Map[String, User] =
                                Map(s"${userRow.organization}/${userRow.username}" -> user) // Ugly mapping, TODO: redesign response body

                          (StatusCodes.OK, GetUsersResponse(userMap, 0))
                    } else {
                          (StatusCodes.NotFound, GetUsersResponse())
                    }
              }.recover {
                    case t: org.postgresql.util.PSQLException =>
                          ExchangePosgtresErrorHandling.ioProblemError(
                                t, ExchMsg.translate("user.not.added", t.toString))

                    case t =>
                          (HttpCode.BAD_INPUT,
                                ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
              }
        })
      }
    }
  // TODO: These hardcoded /apikey and /iamapikey endpoints are temporary workarounds 
  // for legacy components that pass "apikey"/"iamapikey" as username in HTTP requests.
  // Long-term solution: update calling components to use proper authentication instead 
  // of these compatibility endpoints.  
  // =========== GET /orgs/{organization}/users/apikey and /orgs/{organization}/users/iamapikey ================================
  @GET
  @Path("/iamapikey")
  @Operation(summary = "Returns current user info", description = "Returns the authenticated user's own information using IAM apikey authentication.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          
          new Content(
            examples = Array(
              new ExampleObject(
                value =
                  """{
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
  def getUserSelfIamApikey(@Parameter(hidden = true) identity: Identity2,
                           @Parameter(hidden = true) organization: String): Route =
    get {
      if (identity.organization != organization) {
        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.path.mismatch"))))
      } 
      else{
        logger.debug(s"GET /orgs/$organization/users/iamapikey - By ${identity.resource}:${identity.role}")
        
        val getUserWithApiKeys: CompiledStreamingExecutable[Query[(MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, Option[String], UUID, String)], Rep[Option[(Rep[String], Rep[UUID], Rep[String])]], Rep[Option[ApiKeys]]), (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow]), Seq], Seq[(UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])], (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])] =
          for {
            users <-
              Compiled((UsersTQ.filter(user => (user.organization === organization &&
                                              user.username === identity.username))
                              .filter(_.password.isDefined)
                              .take(1)
                              .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                              .on(_.modifiedBy === _._2)
                              .joinLeft(ApiKeysTQ)
                              .on(_._1.user === _.user)
                              .map(users =>
                                    ((users._1._1.createdAt,
                                    users._1._1.email,
                                    users._1._1.identityProvider,
                                    users._1._1.isHubAdmin,
                                    users._1._1.isOrgAdmin,
                                    users._1._1.modifiedAt,
                                    users._1._1.modifiedBy,
                                    users._1._1.organization,
                                    Option(StrConstants.hiddenPw),  // DO NOT grab and return credentials.
                                    users._1._1.user,
                                    users._1._1.username), users._1._2, users._2)))  // Because of the outer-join we cannot touch the content of these values to combine them ((organization, username) => (organization/username)).
                      ++
                      (UsersTQ.filter(user => (user.organization === organization && // Have to retrieve the Some and None values separately to substitute the Some values.
                                              user.username === identity.username))
                              .filter(_.password.isEmpty)
                              .take(1)
                              .joinLeft(UsersTQ.map(users => (users.organization, users.user, users.username)))
                              .on(_.modifiedBy === _._2)
                              .joinLeft(ApiKeysTQ)
                              .on(_._1.user === _.user)
                              .map(users =>
                                    ((users._1._1.createdAt,
                                      users._1._1.email,
                                      users._1._1.identityProvider,
                                      users._1._1.isHubAdmin,
                                      users._1._1.isOrgAdmin,
                                      users._1._1.modifiedAt,
                                      users._1._1.modifiedBy,
                                      users._1._1.organization,
                                      None,
                                      users._1._1.user,
                                      users._1._1.username), users._1._2, users._2))))
          } yield users.map(user => (user._1.mapTo[UserRow], user._2, user._3))
          
        complete({
              db.run(getUserWithApiKeys.result.transactionally).map { result =>
                    // logger.debug(s"GET /orgs/$organization/users/$username result size: " + result.size)

                    if (result.nonEmpty) {
                          val userResult = result.head
                          val userRow = userResult._1
                          val modifiedByInfo = userResult._2

                          val apiKeyMetadataList = result.filter(_._3.isDefined).map { case (_, _, Some(apiKeyRow)) =>
                                new ApiKeyMetadata(apiKeyRow, null)
                          }.distinct

                          val user = new User((userRow, modifiedByInfo), Some(apiKeyMetadataList))
                          val userMap: Map[String, User] =
                                Map(s"${userRow.organization}/${userRow.username}" -> user) // Ugly mapping, TODO: redesign response body

                          (StatusCodes.OK, GetUsersResponse(userMap, 0))
                    } else {
                          (StatusCodes.NotFound, GetUsersResponse())
                    }
              }.recover {
                    case t: org.postgresql.util.PSQLException =>
                          ExchangePosgtresErrorHandling.ioProblemError(
                                t, ExchMsg.translate("user.not.added", t.toString))

                    case t =>
                          (HttpCode.BAD_INPUT,
                                ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
              }
        })
      }
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
    } ~
    path("orgs" / Segment / "users" / "apikey") {
      organization =>
        get {
          val resource = OrgAndId(organization, identity.username).toString
          val resource_identity = identity.identifier
          exchAuth(TUser(resource, resource_identity), Access.READ, validIdentity = identity) {
            _ =>
              getUserSelfApikey(identity, organization)
          }
        }
    } ~
    path("orgs" / Segment / "users" / "iamapikey") {
      organization =>
        get {
          val resource = OrgAndId(organization, identity.username).toString
          val resource_identity = identity.identifier
          exchAuth(TUser(resource, resource_identity), Access.READ, validIdentity = identity) {
            _ =>
              getUserSelfIamApikey(identity, organization)
          }
        }
    }
}

