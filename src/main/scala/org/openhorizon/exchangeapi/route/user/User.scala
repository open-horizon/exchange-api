package org.openhorizon.exchangeapi.route.user

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive0, Route}
import org.apache.pekko.http.scaladsl.server.directives.DebuggingDirectives
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller
import org.apache.pekko.pattern.BackoffOpts.onFailure
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceIdentity, cacheResourceOwnership}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthRoles, AuthenticationSupport, BadInputException, IUser, Identity, Identity2, OrgAndId, Password, Role, TUser}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ, User => UserTable}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyRow, ApiKeys, ApiKeysTQ,ApiKeyMetadata}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, StrConstants}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{CompiledStreamingExecutable, MappedProjection}

import java.lang.ClassNotFoundException
import java.sql.Timestamp
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import scalacache.modes.scalaFuture._
import scala.concurrent.Future


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
  def getUser(@Parameter(hidden = true) identity: Identity2,
              @Parameter(hidden = true) organization: String,
              @Parameter(hidden = true) resource: String,
              @Parameter(hidden = true) username: String): Route =
    get {
      logger.debug(s"GET /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}")
      
      val getUserWithApiKeys: CompiledStreamingExecutable[Query[(MappedProjection[UserRow, (Timestamp, Option[String], String, Boolean, Boolean, Timestamp, Option[UUID], String, Option[String], UUID, String)], Rep[Option[(Rep[String], Rep[UUID], Rep[String])]], Rep[Option[ApiKeys]]), (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow]), Seq], Seq[(UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])], (UserRow, Option[(String, UUID, String)], Option[ApiKeyRow])] =
        for {
          users <-
            Compiled((UsersTQ.filter(user => (user.organization === organization &&
                                             user.username === username))
                            .filterIf(identity.isStandardUser)(users => users.user === identity.identifier.get) // Users can see themselves
                            .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root")) // Organization Admins can see other Org Admins and Users in their Organization. They cannot see Hub Admins, and they cannot see Root.
                            .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root")) // Hub Admins can see other Hub Admins and Organization Admins system-wide. They cannot see Root, and they cannot see normal users.
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
                                             user.username === username))
                            .filterIf(identity.isStandardUser)(users => users.user === identity.identifier.get) // Users can see themselves
                            .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root")) // Organization Admins can see other Org Admins and Users in their Organization. They cannot see Hub Admins, and they cannot see Root.
                            .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root")) // Hub Admins can see other Hub Admins and Organization Admins system-wide. They cannot see Root, and they cannot see normal users.
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

                        val user = new UserTable((userRow, modifiedByInfo), Some(apiKeyMetadataList))
                        val userMap: Map[String, UserTable] =
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

  // =========== POST /orgs/{organization}/users/{username} ===============================
  @POST
  @Operation(
    summary = "Adds a user",
    description = "Creates a new user. This can be run root/root, or a user with admin privilege. This endpoint is not allowed in OAuth mode.",
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
  "hubAdmin": false,
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
  def postUser(@Parameter(hidden = true) identity: Identity2,
               @Parameter(hidden = true) organization: String,
               @Parameter(hidden = true) resource: String,
               @Parameter(hidden = true) username: String): Route = {
    post {
      entity(as[PostPutUsersRequest]) {
        reqBody =>
          logger.debug(s"POST /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}")
          
          validateWithMsg(if(Option(reqBody.password).isEmpty || Option(reqBody.email).isEmpty || reqBody.password == null || reqBody.email == null)
                            Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
                          else if (Set("apikey", "iamapikey").contains(username.toLowerCase)) //block creation of user with reserved usernames 'apikey' or 'iamapikey
                            Option(ExchMsg.translate("user.reserved.name"))  // TO DO: Larger feature around reserved words. Exchange-wide.
                          else if (reqBody.password.isBlank || reqBody.password.isEmpty)
                            Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
                          else if (organization == "root" &&
                                   username == "root") // Can only PATCH Root.
                            Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                          else if (Option(reqBody.password).isEmpty || Option(reqBody.email).isEmpty)
                            Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
                          else if (reqBody.admin &&
                                   reqBody.hubAdmin.getOrElse(false)) // Cannot create Root.
                            Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                          else if (identity.isStandardUser &&
                                   reqBody.admin)
                            Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                          else if (organization == "root" &&
                                   (reqBody.hubAdmin.isEmpty || !reqBody.hubAdmin.getOrElse(false)) &&
                                   (reqBody.admin || !reqBody.admin))
                            Option(ExchMsg.translate("user.cannot.be.in.root.org"))
                          else if (identity.isOrgAdmin &&
                                   !identity.isStandardUser &&
                                   reqBody.hubAdmin.getOrElse(false))
                            Option(ExchMsg.translate("only.super.users.make.hub.admins"))
                          else if (organization != "root" &&
                                   reqBody.hubAdmin.getOrElse(false))
                            Option(ExchMsg.translate("hub.admins.in.root.org"))
                          else if (identity.isHubAdmin &&
                                   !reqBody.admin &&
                                   !reqBody.hubAdmin.getOrElse(false))
                            Option(ExchMsg.translate("hub.admins.only.write.admins"))
                          else
                            None) {
            val timestamp: java.sql.Timestamp = ApiTime.nowUTCTimestamp
            val uuid: java.util.UUID          = UUID.randomUUID()        // version 4
            
            val createUser: DBIOAction[Int, NoStream, Effect.Write] =
              for {
                numUsersCreated <-
                  UsersTQ +=
                    UserRow(createdAt = timestamp,
                            email =
                              if (reqBody.email.isEmpty)
                                None
                              else
                                Option(reqBody.email),
                            isOrgAdmin = reqBody.admin,
                            isHubAdmin = reqBody.hubAdmin.getOrElse(false),
                            modifiedAt = timestamp,
                            modified_by = identity.identifier,
                            organization = organization,
                            password = Option(Password.hash(reqBody.password)),
                            user = uuid,
                            username = username)
                
                // TODO: Auth Cache things
              } yield(numUsersCreated)
            
            complete({
              db.run(createUser.transactionally.asTry).map {
                case Success(result) =>
                  logger.debug("POST /orgs/" + organization + "/users/" + username + " created: " + result)
                  
                  Future {
                    cacheResourceIdentity.put(resource)(value =
                                                          (Identity2(identifier   = Option(uuid),
                                                                     organization = organization,
                                                                     owner        = None,
                                                                     role         =
                                                                       ((reqBody.admin, reqBody.hubAdmin.getOrElse(false)) match {
                                                                         case (true, true) => AuthRoles.SuperUser
                                                                         case (true, false) => AuthRoles.AdminUser
                                                                         case (false, true) => AuthRoles.HubAdmin
                                                                         case (false, false) => AuthRoles.User
                                                                       }),
                                                                     username     = username),
                                                           Password.hash(reqBody.password)),
                                                        ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds))
                    
                  }
                  
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.added.successfully", s"${resource}(Resource:${uuid})")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  t.getMessage match {
                    case message if (message.contains("duplicate key value violates unique constraint")) => // Trying to create the Same User twice.
                      (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added.or.updated.successfully", resource)))
                    case _ =>
                      (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.updated", t.toString)))
                  }
                case Failure(t) =>
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.updated", t.toString)))
              }
            })
          }
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
  "hubAdmin": false,
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
def putUser(@Parameter(hidden = true) identity: Identity2,
            @Parameter(hidden = true) organization: String,
            @Parameter(hidden = true) resource: String,
            @Parameter(hidden = true) username: String): Route =
  put {
    entity(as[PostPutUsersRequest]) {
      reqBody =>
        Future { logger.debug(s"PUT /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}") }
        val isOAuthEnabled = Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url")
        
        validateWithMsg(if(!isOAuthEnabled && (Option(reqBody.password).isEmpty || Option(reqBody.email).isEmpty || reqBody.password == null || reqBody.email == null))
                          Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))  // Tha lack of password disables the account, currently. We do not allow the creation of User accounts in a disabled state to begin with.
                        else if (!isOAuthEnabled && (reqBody.password.isBlank || reqBody.password.isEmpty))
                          Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
                        else if (organization == "root" &&
                                 username == "root") // Can only PATCH Root.
                          Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                        else if (!isOAuthEnabled && (Option(reqBody.password).isEmpty || Option(reqBody.email).isEmpty))
                          Option(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
                        else if (reqBody.admin &&
                                 reqBody.hubAdmin.getOrElse(false)) // Cannot create Root.
                          Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                        else if (identity.isStandardUser &&
                                 reqBody.admin)
                          Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                        else if (organization == "root" &&
                                 (reqBody.hubAdmin.isEmpty || !reqBody.hubAdmin.getOrElse(false)) &&
                                 (reqBody.admin || !reqBody.admin))
                          Option(ExchMsg.translate("user.cannot.be.in.root.org"))
                        else if (identity.isOrgAdmin &&
                                 !identity.isSuperUser &&
                                 reqBody.hubAdmin.getOrElse(false))
                          Option(ExchMsg.translate("only.super.users.make.hub.admins"))
                        else if (organization != "root" &&
                                 reqBody.hubAdmin.getOrElse(false))
                          Option(ExchMsg.translate("hub.admins.in.root.org"))
                        else if (!isOAuthEnabled && reqBody.email.isEmpty)
                          Option(ExchMsg.translate("bad.input"))
                        else if (identity.isHubAdmin &&
                                 !reqBody.admin &&
                                 !reqBody.hubAdmin.getOrElse(false))
                          Option(ExchMsg.translate("hub.admins.only.write.admins"))
                        else
                          None) {
          
          val timestamp: java.sql.Timestamp = ApiTime.nowUTCTimestamp
          val uuid: java.util.UUID          = UUID.randomUUID()        // version 4
          
          // Extract common query filters
          val userQuery = UsersTQ.filter(user => (user.organization === organization && user.username === username))
                                .filterIf(identity.isUser && !identity.isSuperUser)(users => (users.organization ++ "/" ++ users.username) =!= "root/root")
                                .filterIf(identity.isStandardUser)(users => users.user === identity.identifier)
                                .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin)
                                .filterIf(identity.isHubAdmin)(user => (user.isHubAdmin || user.isOrgAdmin))
          
          // Update all fields helper
          val updateAllFields = Compiled(userQuery.map(user =>
            (user.email,
            user.isOrgAdmin,
            user.isHubAdmin,
            user.modifiedAt, 
            user.modifiedBy,
            user.password)))
                    .update((if (reqBody.email.isEmpty)
                               None
                             else
                               Option(reqBody.email),
                    reqBody.admin,
                    reqBody.hubAdmin.getOrElse(false),
                    timestamp,
                    identity.identifier,
                    Option(Password.hash(reqBody.password))))
          
          // Check if user is external when OAuth is enabled - done inside DBIO to avoid unnecessary queries
          val createOrModifyUser =
            for { 
              isExternalUser <- 
                if (isOAuthEnabled) {
                  UsersTQ.filter(user => user.organization === organization && user.username === username)
                    .filter(_.identityProvider =!= "Open Horizon")
                    .take(1)
                    .length
                    .result
                } else {
                  DBIO.successful(0)
                }
              
              numUsersModified <-
                if (isOAuthEnabled && isExternalUser > 0) {
                  // External user: only update admin permissions
                  Compiled(userQuery.map(user => 
                    (user.isOrgAdmin,
                     user.isHubAdmin, 
                     user.modifiedAt, 
                     user.modifiedBy)))
                    .update((reqBody.admin, reqBody.hubAdmin.getOrElse(false), timestamp, identity.identifier))
                } else {
                  // Local user or non-OAuth: update all fields
                  updateAllFields
                }
                  
                  numUsersCreated <-
                    if (numUsersModified.equals(0)) {
                      if (isOAuthEnabled){
                        DBIO.failed(new MethodNotAllowedException(ExchMsg.translate("user.creation.disabled.oauth")))
                      } else {
                        UsersTQ +=
                          UserRow(createdAt = timestamp,
                                  email =
                                    if (reqBody.email.isEmpty)
                                      None
                                    else
                                      Option(reqBody.email),
                                  isOrgAdmin = reqBody.admin,
                                  isHubAdmin = reqBody.hubAdmin.getOrElse(false),
                                  modifiedAt = timestamp,
                                  modified_by = identity.identifier,
                                  organization = organization,
                                  password = Option(Password.hash(reqBody.password)),
                                  user = uuid,
                                  username = username)
                      }
                    } else
                        DBIO.successful(0)
                  
                  // TODO: Auth Cache things
                    
                } yield(numUsersCreated, numUsersModified)
              
              complete({
                db.run(createOrModifyUser.transactionally.asTry).map {
                  case Success(result) =>
                    Future { logger.debug("PUT /orgs/" + organization + "/users/" + username + " - created: " + result._1 + " modified: " + result._2) }
                    
                    Future {
                      if (result._1 == 1)
                        cacheResourceIdentity.put(resource)(value =
                                                            (Identity2(identifier   = Option(uuid),
                                                                       organization = organization,
                                                                       owner        = None,
                                                                       role         =
                                                                         ((reqBody.admin, reqBody.hubAdmin.getOrElse(false)) match {
                                                                           case (true, true) => AuthRoles.SuperUser
                                                                           case (true, false) => AuthRoles.AdminUser
                                                                           case (false, true) => AuthRoles.HubAdmin
                                                                           case (false, false) => AuthRoles.User
                                                                         }),
                                                                       username     = username),
                                                            if (isOAuthEnabled) "" else Password.hash(reqBody.password)),
                                                          ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds))
                      else
                        cacheResourceIdentity.remove(resource)
                    }

                    
                    if (result._1 == 1)
                      (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.added.successfully", s"${resource}(Resource:${uuid})")))
                    else
                      (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.updated.successfully")))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    t.getMessage match {
                      case message if (message.contains("duplicate key value violates unique constraint")) => // Trying to create the Same User twice.
                        (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added.or.updated.successfully", resource)))
                      case message if (message.contains("violates foreign key constraint")) =>
                        (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.added.or.updated.successfully", resource)))
                      case _ =>
                        (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.updated", t.toString)))
                    }
                  case Failure(t: MethodNotAllowedException) =>
                    (StatusCodes.MethodNotAllowed, ApiResponse(ApiRespType.METHOD_NOT_ALLOWED, ExchMsg.translate("user.creation.disabled.oauth")))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
                }
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
  "hubAdmin": false,
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
  def patchUser(@Parameter(hidden = true) identity: Identity2,
                @Parameter(hidden = true) organization: String,
                @Parameter(hidden = true) resource: String,
                @Parameter(hidden = true) username: String): Route =
    patch {
      entity(as[PatchUsersRequest]) {
        reqBody =>
          logger.debug(s"PATCH /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}")

          val attributeExistence: Seq[(String, Boolean)] =
            Seq(("admin",reqBody.admin.isDefined),
                ("email", reqBody.email.isDefined),
                ("hubadmin", reqBody.hubAdmin.isDefined),
                ("password", reqBody.password.isDefined))
          
          validate(((attributeExistence.filter(_._2).sizeIs == 1)), ExchMsg.translate("bad.input")) {
            // Have a single attribute to update, retrieve its name.
            val validAttribute: String =
              attributeExistence.filter(attribute => attribute._2).head._1

            // Check if user is external when OAuth is enabled and trying to modify password/email
            val isOAuthEnabled = Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url")
            
            validateWithMsg(// ----- OAuth restrictions -----
                            if (isOAuthEnabled && (validAttribute == "email" || validAttribute == "password")) {
                              val isExternalUser = Await.result(db.run(UsersTQ.filter(user => user.organization === organization && user.username === username)
                                .filter(_.identityProvider =!= "Open Horizon")
                                .take(1)
                                .length
                                .result), 5.seconds)
                              if (isExternalUser > 0)
                                Option(ExchMsg.translate("user.attr.not.allowed.oauth", validAttribute))
                              else
                                None
                            }
                            // ----- Root -----
                            else if (resource == "root/root" && !identity.isSuperUser)
                              Option(ExchMsg.translate("user.not.updated", username))
                            else if (resource == "root/root" && (validAttribute == "admin" || validAttribute == "hubadmin"))
                              Option(ExchMsg.translate("user.not.updated", username))
                            // ----- admin -----
                            else if ((identity.isHubAdmin || identity.isStandardUser) &&
                                     reqBody.admin.getOrElse(false))
                              Option(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
                            // ----- email -----
                            else if (validAttribute == "email" &&
                                     (reqBody.email.get.isEmpty || reqBody.email.get == null))
                              Option(ExchMsg.translate("bad.input"))
                            // ----- hubadmin -----
                            else if ((identity.isOrgAdmin || identity.isStandardUser) &&
                                     reqBody.hubAdmin.getOrElse(false))
                              Option(ExchMsg.translate("only.super.users.make.hub.admins"))
                            else if (organization != "root" &&
                                     reqBody.hubAdmin.getOrElse(false))
                              Option(ExchMsg.translate("hub.admins.in.root.org"))
                            // ----- Users -----
                            else if (identity.isStandardUser && resource != identity.resource)
                              Option(ExchMsg.translate("bad.input"))
                            else if (identity.isOrgAdmin && organization != identity.organization)
                              Option(ExchMsg.translate("bad.input"))
                            else
                              None) {
              
              val timestamp: Timestamp = ApiTime.nowUTCTimestamp
              
              val modifyUserAttribute: DBIOAction[Int, NoStream, Effect.Write with Effect] =
                for {
                  numUsersModified <-
                    if (validAttribute == "admin")
                      Compiled(UsersTQ.filter(users => (!users.isHubAdmin && // Filter out any current Hub Admins. Includes Root.
                                                        users.organization === organization &&
                                                        users.username === username) &&
                                                        (users.organization ++ "/" ++ users.username) =!= "root/root") // Guard, filter out Root specifically.
                                      .filterIf(identity.isStandardUser)(users => users.user === identity.identifier && 0.asColumnOf[Int] === 1)  // Users will not yield any records.
                                      .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin)
                                      .map(user => (user.isOrgAdmin,
                                                    user.modifiedAt,
                                                    user.modifiedBy)))
                                      .update(reqBody.admin.get,
                                              timestamp,
                                              identity.identifier)
                    else if (validAttribute == "hubadmin")
                      Compiled(UsersTQ.filter(users => (!users.isOrgAdmin && // Filter out any current Organization Admins. Includes Root.
                                                        users.organization === organization &&
                                                        users.username === username &&
                                                        (users.organization ++ "/" ++ users.username) =!= "root/root")) // Guard, filter out Root specifically.
                                      .filterIf(identity.isStandardUser)(users => users.user === identity.identifier && 0.asColumnOf[Int] === 1)  // Users will not yield any records.
                                      .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin && 0.asColumnOf[Int] === 1)  // Organization Admins will not yield any records.
                                      .filterIf(identity.isHubAdmin)(user => (user.isHubAdmin || user.isOrgAdmin)) // Hub Admins may only remove other Hub Admins. They cannot promote Users to Hub Admin.
                                      .map(user =>
                                            (user.isHubAdmin,
                                             user.modifiedAt,
                                             user.modifiedBy)))
                                      .update(reqBody.hubAdmin.get,
                                              timestamp,
                                              identity.identifier)
                    else
                      Compiled(UsersTQ.filter(users => (users.organization === organization &&
                                                        users.username === username))
                                      .filterIf(identity.isUser && !identity.isSuperUser)(users => (users.organization ++ "/" ++ users.username) =!= "root/root") // Only Root may change themselves.
                                      .filterIf(identity.isStandardUser)(users => users.user === identity.identifier)
                                      .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin)
                                      .filterIf(identity.isHubAdmin)(user => (user.isHubAdmin || user.isOrgAdmin))
                                      .map(user =>
                                            (validAttribute match {
                                               case "email" => user.email
                                               case "password" => user.password
                                             },
                                             user.modifiedAt,
                                             user.modifiedBy)))
                                      .update((
                                        validAttribute match {
                                          case "email" => reqBody.email
                                          case "password" =>
                                            if (reqBody.password.getOrElse("").isEmpty)
                                              None
                                            else
                                              Option(Password.hash(reqBody.password.getOrElse(""))) // We wait till the last second. Avoids creating more spots in memory where this is stored.
                                        },
                                        timestamp,
                                        identity.identifier))
                  
                  _ <-
                    if (numUsersModified.equals(0))
                      DBIO.failed(new ClassNotFoundException())
                    else
                      DBIO.successful(0)
                  
                  // TODO: Auth Cache things
                } yield (numUsersModified)
              
              complete({
                db.run(modifyUserAttribute.transactionally.asTry).map {
                  case Success(result) =>
                    Future { logger.debug("PATCH /orgs/" + organization + "/users/" + username + " - result: " + result) }
                    
                    Future {
                      if (validAttribute == "password" &&
                          resource == identity.resource)
                        cacheResourceIdentity.put(resource)(value = (identity, Password.hash(reqBody.password.getOrElse(""))),
                          ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds))
                      else
                        cacheResourceIdentity.remove(resource)
                    }
                    
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.attr.updated", validAttribute, resource)))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.updated", t.toString))
                  case Failure(t: ClassNotFoundException) =>
                    logger.debug("NOT FOUND    PATCH /orgs/" + organization + "/users/" + username + "    attribute: " + validAttribute) // Either the User does not exist, or [...] Admin permission shenanigans.
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
                }
              })
            }
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
  def deleteUser(@Parameter(hidden = true) identity: Identity2,
                 @Parameter(hidden = true) organization: String,
                 @Parameter(hidden = true) resource: String,
                 @Parameter(hidden = true) username: String): Route =
    delete {
      Future { logger.debug(s"DELETE /orgs/$organization/users/$username - By ${identity.resource}:${identity.role}") }
      
      validate(organization + "/" + username != Role.superUser, ExchMsg.translate("cannot.delete.root.user")) {
        
        val deleteUser: DBIOAction[Int, NoStream, Effect.Write with Effect] =
          for {
            numUsersDeleted <-
              Compiled(UsersTQ.filter(user => (user.organization === organization &&
                                               user.username === username) &&
                                              (user.organization ++ "/" ++ user.username) =!= "root/root")
                              .filterIf(identity.isStandardUser)(users => users.user === identity.identifier)
                              .filterIf(identity.isOrgAdmin)(users => users.organization === identity.organization && !users.isHubAdmin)
                              .filterIf(identity.isHubAdmin)(user => (user.isHubAdmin || user.isOrgAdmin)))
                  .delete
            
            _ <- // Guards
              if (numUsersDeleted.equals(0))
                DBIO.failed(new ClassNotFoundException())
              else if (1 < numUsersDeleted) {
                DBIO.failed(throw new ArrayIndexOutOfBoundsException())
              } else
                DBIO.successful(0)
          } yield(numUsersDeleted)
          
        complete {
          db.run(deleteUser.transactionally.asTry).map {
            case Success(result) =>
              Future { cacheResourceIdentity.remove(resource) }
              
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.deleted")))
            case Failure(t: ClassNotFoundException) =>
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", resource)))
            case Failure(t: ArrayIndexOutOfBoundsException) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.deleted", resource, "")))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.deleted", resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.deleted", resource, t.toString)))
          }
        }
      }
    }

  def oauthEnabledCheck: Directive0 = {
    if (Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url"))  {
      complete(StatusCodes.MethodNotAllowed -> ApiResponse(ApiRespType.METHOD_NOT_ALLOWED, ExchMsg.translate("api.endpoint.disabled.oauth")))
    } else pass
  }
  
  def user(identity: Identity2): Route = {
    path("orgs" / Segment / "users"/ Segment) {
      (organization,
       username) =>
        val resource: String = OrgAndId(organization, username).toString
        val resource_type: String = "user"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, username, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(resource_identity: Option[UUID]): Route = {
          (delete | patch | put) {
            exchAuth(TUser(resource, resource_identity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteUser(identity, organization, resource, username) ~
                patchUser(identity, organization, resource, username) ~
                putUser(identity, organization, resource, username)
            }
          } ~
          get {
            exchAuth(TUser(resource, resource_identity), Access.READ, validIdentity = identity) {
              _ =>
                getUser(identity, organization, resource, username)
            }
          } ~
          post {
            oauthEnabledCheck {
              exchAuth(TUser(resource, resource_identity), Access.CREATE, validIdentity = identity) {
                _ =>
                  postUser(identity, organization, resource, username)
              }
            }
          }
        }
        
        onComplete(cacheCallback) {
          case Success((resource_identity, _)) => routeMethods(Option(resource_identity))
          case Failure(_) => routeMethods(None)
        }
    }
  }
}
