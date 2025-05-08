package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, patch, path, post, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, BadInputException, IUser, Identity, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{User => UserTable, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeysTQ,ApiKeyMetadata}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{ExecutionContext,Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/users/{username}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user")
trait User extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== GET /orgs/{organization}/users/{username} ================================
  @GET
  @Operation(summary = "Returns a user", description = "Returns the specified username. Can only be run by that user or root.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
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
          "id": "uuid",
          "description": "string",
          "created_at": "string",
          "created_by": "string",
          "modified_at": "string",
          "modified_by": "string"
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
  def getUser(@Parameter(hidden = true) identity: Identity,
              @Parameter(hidden = true) organization: String,
              @Parameter(hidden = true) resource: String,
              @Parameter(hidden = true) username: String): Route =
    get {
      logger.debug(s"Doing GET /orgs/$organization/users/$username")
      
      complete({
        logger.debug(s"GET /orgs/$organization/users/$username identity: ${identity.creds.id}") // can't display the whole ident object, because that contains the pw/token
        var realUsername: String = username
        val realResource: String =
          if (username == "iamapikey" || username == "iamtoken") {
            // Need to change the target into the username that the key resolved to
            realUsername = identity.getIdentity
            OrgAndId(identity.getOrg, identity.getIdentity).toString
          }
          else
            resource
        val query =
          if (identity.isHubAdmin && !identity.isSuperUser)
            UsersTQ.getUserIfAdmin(realResource)
          else
            UsersTQ.getUser(realResource)
      db.run(query.result).flatMap { list =>
        if (list.nonEmpty) {
        val userRow = list.head

        db.run(ApiKeysTQ.getByUser(userRow.username).result).map { keys =>
        val keyMetadata = keys.map(row =>
          ApiKeyMetadata(
          id = row.id,
          description = row.description,
          user = null, 
          createdAt = row.createdAt,
          createdBy = row.createdBy,
          modifiedAt = row.modifiedAt,
          modifiedBy = row.modifiedBy ))

        val user = org.openhorizon.exchangeapi.table.user.User(
        password = if (identity.isSuperUser || identity.isHubAdmin) userRow.hashedPw else StrConstants.hiddenPw,
        admin = userRow.admin,
        hubAdmin = userRow.hubAdmin,
        email = userRow.email,
        lastUpdated = userRow.lastUpdated,
        updatedBy = userRow.updatedBy,
        apikeys = Some(keyMetadata)
      )

      val usersMap = Map(userRow.username -> user)
      (StatusCodes.OK, GetUsersResponse(usersMap, 0))
    }
  } else {
      Future.successful((StatusCodes.NotFound, GetUsersResponse(Map.empty, 0)))
  }
}
      })
    }
  
  // =========== POST /orgs/{organization}/users/{username} ===============================
  @POST
  @Operation(
    summary = "Adds a user",
    description = "Creates a new user. This can be run root/root, or a user with admin privilege.",
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
              value =
                """{
  "password": "abc",
  "admin": false,
  "email": "me@gmail.com"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutUsersRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
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
  def postUser(@Parameter(hidden = true) identity: Identity,
               @Parameter(hidden = true) organization: String,
               @Parameter(hidden = true) resource: String,
               @Parameter(hidden = true) username: String): Route =
    post {
      entity(as[PostPutUsersRequest]) {
        reqBody =>
          logger.debug(s"Doing POST /orgs/$organization/users/$username")
          logger.debug("isAdmin: " + identity.isAdmin + ", isHubAdmin: " + identity.isHubAdmin + ", isSuperUser: " + identity.isSuperUser)
          validateWithMsg(reqBody.getAnyProblem(identity, organization, resource, isPost = true)) {
            complete({
              val updatedBy: String = identity match {
                case IUser(identCreds) => identCreds.id;
                case _ => ""
              }
              val hashedPw: String = Password.hash(reqBody.password)
              /* Note: this kind of check and error msg in the body of complete({}) does not work (it returns the error msg, but the response code is still 200). This kind of access check belongs in AuthorizationSupport (which is invoked by exchAuth()) or in getAnyProblem().
                if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin) (HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("hub.admins.only.write.admins"))
                else */
              db.run(UserRow(resource, organization, hashedPw, reqBody.admin, reqBody.hubAdmin.getOrElse(false), reqBody.email, ApiTime.nowUTC, updatedBy).insertUser().asTry).map({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/users/" + username + " result: " + v)
                  AuthCache.putUserAndIsAdmin(resource, hashedPw, reqBody.password, reqBody.admin)
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.added.successfully", v)))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added", t.toString)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.added", t.toString))
                case Failure(t: BadInputException) => t.toComplete
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added", t.toString)))
              })
            })
          }
      }
    }
  
  // =========== PUT /orgs/{organization}/users/{username} ================================
  @PUT
  @Operation(summary = "Updates a user", description = "Updates an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value =
              """{
  "password": "abc",
  "admin": false,
  "email": "me@gmail.com"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutUsersRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putUser(@Parameter(hidden = true) identity: Identity,
              @Parameter(hidden = true) organization: String,
              @Parameter(hidden = true) resource: String,
              @Parameter(hidden = true) username: String): Route =
    put {
      entity(as[PostPutUsersRequest]) {
        reqBody =>
          logger.debug(s"Doing PUT /orgs/$organization/users/$username")
          
          validateWithMsg(reqBody.getAnyProblem(identity, organization, resource, isPost = false)) {
            complete({
              val updatedBy: String = identity match {
                case IUser(identCreds) => identCreds.id;
                case _ => ""
              }
              val hashedPw: String = Password.hash(reqBody.password)
              db.run(UserRow(resource, organization, hashedPw, reqBody.admin, reqBody.hubAdmin.getOrElse(false), reqBody.email, ApiTime.nowUTC, updatedBy).updateUser().asTry).map({
                case Success(n) =>
                  logger.debug("PUT /orgs/" + organization + "/users/" + username + " result: " + n)
                  if (n.asInstanceOf[Int] > 0) {
                    AuthCache.putUserAndIsAdmin(resource, hashedPw, reqBody.password, reqBody.admin)
                    (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.updated.successfully")))
                  } else {
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
                  }
                case Failure(t: org.postgresql.util.PSQLException) =>
                  ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.updated", t.toString))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
              })
            })
          }
      }
    }
  
  // =========== PATCH /orgs/{organization}/users/{username} ==============================
  @PATCH
  @Operation(summary = "Updates 1 attribute of a user", description = "Updates 1 attribute of an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes:", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value =
              """{
  "password": "abc",
  "admin": false,
  "email": "me@gmail.com"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchUsersRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patchUser(@Parameter(hidden = true) identity: Identity,
                @Parameter(hidden = true) organization: String,
                @Parameter(hidden = true) resource: String,
                @Parameter(hidden = true) username: String): Route =
    patch {
      entity(as[PatchUsersRequest]) {
        reqBody =>
          logger.debug(s"Doing POST /orgs/$organization/users/$username")
          
          validateWithMsg(reqBody.getAnyProblem(identity, organization, resource)) {
            complete({
              val updatedBy: String =
                identity match {
                  case IUser(identCreds) => identCreds.id;
                  case _ => ""
                }
              val hashedPw: String = if (reqBody.password.isDefined) Password.hash(reqBody.password.get) else "" // hash the pw if that is what is being updated
              val (action, attrName) = reqBody.getDbUpdate(resource, organization, updatedBy, hashedPw)
              if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.agbot.attr.specified")))
              db.run(action.transactionally.asTry).map({
                case Success(n) =>
                  logger.debug("PATCH /orgs/" + organization + "/users/" + username + " result: " + n)
                  if (n.asInstanceOf[Int] > 0) {
                    if (reqBody.password.isDefined) AuthCache.putUser(resource, hashedPw, reqBody.password.get)
                    if (reqBody.admin.isDefined) AuthCache.putUserIsAdmin(resource, reqBody.admin.get)
                    (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.attr.updated", attrName, resource)))
                  } else {
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
                  }
                case Failure(t: org.postgresql.util.PSQLException) =>
                  ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.updated", t.toString))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
              })
            })
          }
      }
    }
  
  // =========== DELETE /orgs/{organization}/users/{username} =============================
  @DELETE
  @Operation(summary = "Deletes a user", description = "Deletes a user and all of its nodes and agbots. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteUser(@Parameter(hidden = true) organization: String,
                 @Parameter(hidden = true) resource: String,
                 @Parameter(hidden = true) username: String): Route =
    delete {
      logger.debug(s"Doing DELETE /orgs/$organization/users/$username")
      
      validate(organization + "/" + username != Role.superUser, ExchMsg.translate("cannot.delete.root.user")) {
        complete({
          // Note: remove does *not* throw an exception if the key does not exist
          //todo: if ident.isHubAdmin then 1st get the target user row to verify it isn't a regular user
          db.run(UsersTQ.getUser(resource).delete.transactionally.asTry).map({
            case Success(v) => // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$organization/users/$username result: $v")
              if (v > 0) {
                AuthCache.removeUser(resource) // these do not throw an error if the user doesn't exist
                //IbmCloudAuth.removeUserKey(compositeId) //todo: <- doesn't work because the IAM cache key includes the api key, which we don't know at this point. Address this in https://github.com/open-horizon/exchange-api/issues/232
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.deleted")))
              }
              else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.deleted", resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.deleted", resource, t.toString)))
          })
        })
      }
    }
  
  val user: Route =
    path("orgs" / Segment / "users" / Segment) {
      (organization, username) =>
        val resource: String = OrgAndId(organization, username).toString
        
        (delete | patch | put) {
          exchAuth(TUser(resource), Access.WRITE) {
            identity =>
              deleteUser(organization, resource, username) ~
              patchUser(identity, organization, resource, username) ~
              putUser(identity, organization, resource, username)
          }
        } ~
          get {
            exchAuth(TUser(resource), Access.READ) {
              identity =>
                getUser(identity, organization, resource, username)
            }
          } ~
          post {
            exchAuth(TUser(resource), Access.CREATE) {
              identity =>
                postUser(identity, organization, resource, username)
            }
          }
    }
}
