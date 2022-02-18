/** Services routes for all of the /users api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

//import com.horizon.exchangeapi.AuthenticationSupport._
import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.auth.{BadInputException, DBProcessingError, IbmCloudAuth}
import org.json4s._

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util._

import slick.jdbc.PostgresProfile.api._


//====== These are the input and output structures for /users routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /users */
final case class GetUsersResponse(users: Map[String, User], lastIndex: Int)

/** Input format for PUT /users/<username> */
final case class PostPutUsersRequest(password: String, admin: Boolean, hubAdmin: Option[Boolean], email: String) {
  require(password!=null && email!=null)
  def getAnyProblem(ident: Identity, orgid: String, compositeId: String, isPost: Boolean): Option[String] = {
    // Note: AuthorizationSupport.IUser does some permission checking for this route, but it can't make decisions based on the request body content,
    //        so we have to do those checks here. For example, non-root trying to create/modify root is caught there.
    // Also Note: AuthCache methods can't be used here because they aren't always up to date on every exchange instance.
    // Reminder: ident.isHubAdmin and ident.isAdmin are both true for the root user too

    if ((password == "") && !ident.isHubAdmin) Some(ExchMsg.translate("password.must.be.non.blank.when.creating.user"))
    // ensure a regular user can't elevate himself to admin user, or admin user elevate to hub admin
    else if (admin && !ident.isAdmin && !ident.isHubAdmin) Some(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    else if (hubAdmin.getOrElse(false) && !ident.isHubAdmin) Some(ExchMsg.translate("only.super.users.make.hub.admins"))
    // hub admin users have to be in the root org and org admins or regular users in a non-root org
    else if (hubAdmin.getOrElse(false) && orgid != "root") Some(ExchMsg.translate("hub.admins.in.root.org"))
    else if (!hubAdmin.getOrElse(false) && orgid == "root") Some(ExchMsg.translate("user.cannot.be.in.root.org"))
    // hub admins can only create hub admins or admins, or modify a user to be a hub admin or admin
    else if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin) Some(ExchMsg.translate("hub.admins.only.write.admins"))
    else if (admin && hubAdmin.getOrElse(false)) Some("User cannot be admin and hubAdmin at the same time") //TODO: Translate this error message
    else None // None means no problems with input
  }
}

final case class PatchUsersRequest(password: Option[String], admin: Option[Boolean], hubAdmin: Option[Boolean], email: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(ident: Identity, orgid: String, compositeId: String): Option[String] = {
    // Note: AuthorizationSupport.IUser does some permission checking for this route, but it can't make decisions based on the request body content,
    //        so we have to do those checks here. For example, non-root trying to create/modify root is caught there.
    // Also Note: AuthCache methods can't be used here because they aren't always up to date on every exchange instance.
    // Reminder: ident.isHubAdmin and ident.isAdmin are both true for the root user too

    if (password.isDefined && password.get == "" && !ident.isHubAdmin) Some(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    // ensure a regular user can't elevate himself to admin user, or admin user elevate to hub admin
    else if (admin.getOrElse(false) && !ident.isAdmin && !ident.isHubAdmin) Some(ExchMsg.translate("non.admin.user.cannot.make.admin.user"))
    else if (hubAdmin.getOrElse(false) && !ident.isHubAdmin) Some(ExchMsg.translate("only.super.users.make.hub.admins"))
    // hub admin users have to be in the root org and org admins in a non-root org
    else if (hubAdmin.getOrElse(false) && orgid != "root") Some(ExchMsg.translate("hub.admins.in.root.org"))
    else if (admin.getOrElse(false) && orgid == "root") Some(ExchMsg.translate("user.cannot.be.in.root.org"))
    // Hub admins can only modify a user to be a hub admin or admin. This check unfortunately prevents a hub admin from changing the
    // password or email, but those are rarely done, so this is better than no check at all.
    else if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin.getOrElse(false)) Some(ExchMsg.translate("hub.admins.only.write.admins"))
    else if (admin.getOrElse(false) && hubAdmin.getOrElse(false)) Some("User cannot be admin and hubAdmin at the same time") //TODO: Translate this error message
    else None // None means no problems with input
  }

  /** Returns a tuple of the db action to update parts of the user, and the attribute name being updated. */
  def getDbUpdate(username: String, orgid: String, updatedBy: String, hashedPw: String): (DBIO[_], String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    password match {
      case Some(_) =>
        //val pw = if (Password.isHashed(password2)) password2 else Password.hash(password2)
        return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.password, u.lastUpdated, u.updatedBy)).update((username, hashedPw, lastUpdated, updatedBy)), "password")
      case _ => ;
    }
    admin match { case Some(admin2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.admin, u.lastUpdated, u.updatedBy)).update((username, admin2, lastUpdated, updatedBy)), "admin"); case _ => ; }
    hubAdmin match { case Some(hubAdmin2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.hubAdmin, u.lastUpdated, u.updatedBy)).update((username, hubAdmin2, lastUpdated, updatedBy)), "hubAdmin"); case _ => ; }
    email match { case Some(email2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.email, u.lastUpdated, u.updatedBy)).update((username, email2, lastUpdated, updatedBy)), "email"); case _ => ; }
    (null, null)
  }
}

/** Input body for POST /orgs/{orgid}/users/{username}/changepw */
final case class ChangePwRequest(newPassword: String) {
  require(newPassword!=null)
  def getAnyProblem: Option[String] = {
    if (newPassword == "") Some(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    else None // None means no problems with input
  }

  def getDbUpdate(username: String, orgid: String, hashedPw: String): DBIO[_] = {
    val lastUpdated: String = ApiTime.nowUTC
    //val pw = if (Password.isHashed(newPassword)) newPassword else Password.hash(newPassword)
    (for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.password, u.lastUpdated)).update((username, hashedPw, lastUpdated))
  }
}

/** Implementation for all of the /users routes */
@Path("/v1/orgs/{orgid}/users")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user")
trait UsersRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def usersRoutes: Route = usersGetRoute ~ userGetRoute ~ userPostRoute ~ userPutRoute ~ userPatchRoute ~ userDeleteRoute ~ userConfirmRoute ~ userChangePwRoute

  /* ====== GET /orgs/{orgid}/users ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all users", description = "Returns all users. Can only be run by the root user, org admins, and hub admins.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
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
    },
      ...
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
  def usersGetRoute: Route = (path("orgs" / Segment / "users") & get) { (orgid) =>
    logger.debug(s"Doing GET /orgs/$orgid/users")
    exchAuth(TUser(OrgAndId(orgid, "#").toString), Access.READ) { ident =>
      complete({
        logger.debug(s"GET /orgs/$orgid/users identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
        val query = if (ident.isHubAdmin && !ident.isSuperUser) UsersTQ.getAllAdmins(orgid) else UsersTQ.getAllUsers(orgid)
        db.run(query.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/users result size: ${list.size}")
          val users: Map[String, User] = list.map(e => e.username -> User(if (ident.isSuperUser || ident.isHubAdmin) e.hashedPw else StrConstants.hiddenPw, e.admin, e.hubAdmin, e.email, e.lastUpdated, e.updatedBy)).toMap
          val code: StatusCode with Serializable = if (users.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetUsersResponse(users, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/users/{username} ================================ */
  @GET
  @Path("{username}")
  @Operation(summary = "Returns a user", description = "Returns the specified username. Can only be run by that user or root.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
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
  def userGetRoute: Route = (path("orgs" / Segment / "users" / Segment) & get) { (orgid, username) =>
    logger.debug(s"Doing GET /orgs/$orgid/users/$username")
    var compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.READ) { ident =>
      complete({
        logger.debug(s"GET /orgs/$orgid/users/$username identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
        var realUsername: String = username
        if (username == "iamapikey" || username == "iamtoken") {
          // Need to change the target into the username that the key resolved to
          realUsername = ident.getIdentity
          compositeId = OrgAndId(ident.getOrg, ident.getIdentity).toString
        }
        val query = if (ident.isHubAdmin && !ident.isSuperUser) UsersTQ.getUserIfAdmin(compositeId) else UsersTQ.getUser(compositeId)
        db.run(query.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/users/$realUsername result size: ${list.size}")
          val users: Map[String, User] = list.map(e => e.username -> User(if (ident.isSuperUser || ident.isHubAdmin) e.hashedPw else StrConstants.hiddenPw, e.admin, e.hubAdmin, e.email, e.lastUpdated, e.updatedBy)).toMap
          val code: StatusCode with Serializable = if (users.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetUsersResponse(users, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/users/{username} ===============================
  @POST
  @Path("{username}")
  @Operation(
    summary = "Adds a user",
    description = "Creates a new user. This can be run root/root, or a user with admin privilege.",
    parameters = Array(
      new Parameter(
        name = "orgid",
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
  "password": "abc",       // the user password this new user should have
  "admin": false,          // if true, this user will have full privilege within the organization
  "email": "me@gmail.com"  // contact email address for this user
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
  def userPostRoute: Route = (path("orgs" / Segment / "users" / Segment) & post & entity(as[PostPutUsersRequest])) { (orgid, username, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.CREATE) { ident =>
      logger.debug("isAdmin: " + ident.isAdmin + ", isHubAdmin: " + ident.isHubAdmin + ", isSuperUser: " + ident.isSuperUser)
      validateWithMsg(reqBody.getAnyProblem(ident, orgid, compositeId, true)) {
        complete({
          val updatedBy: String = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
          val hashedPw: String = Password.hash(reqBody.password)
          /* Note: this kind of check and error msg in the body of complete({}) does not work (it returns the error msg, but the response code is still 200). This kind of access check belongs in AuthorizationSupport (which is invoked by exchAuth()) or in getAnyProblem().
          if (ident.isHubAdmin && !ident.isSuperUser && !hubAdmin.getOrElse(false) && !admin) (HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("hub.admins.only.write.admins"))
          else */
          db.run(UserRow(compositeId, orgid, hashedPw, reqBody.admin, reqBody.hubAdmin.getOrElse(false), reqBody.email, ApiTime.nowUTC, updatedBy).insertUser().asTry).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/users/" + username + " result: " + v)
              AuthCache.putUserAndIsAdmin(compositeId, hashedPw, reqBody.password, reqBody.admin)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.added.successfully", v)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added", t.toString)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.added", t.toString))
            case Failure(t: BadInputException) => t.toComplete
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added", t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/users/{username} ===============================
  @PUT
  @Path("{username}")
  @Operation(summary = "Updates a user", description = "Updates an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "password": "abc",       // the user password this new user should have
  "admin": false,          // if true, this user will have full privilege within the organization
  "email": "me@gmail.com"  // contact email address for this user
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
  def userPutRoute: Route = (path("orgs" / Segment / "users" / Segment) & put & entity(as[PostPutUsersRequest])) { (orgid, username, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgid/users/$username")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem(ident, orgid, compositeId, false)) {
        complete({
          val updatedBy: String = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
          val hashedPw: String = Password.hash(reqBody.password)
          db.run(UserRow(compositeId, orgid, hashedPw, reqBody.admin, reqBody.hubAdmin.getOrElse(false), reqBody.email, ApiTime.nowUTC, updatedBy).updateUser().asTry).map({
            case Success(n) =>
              logger.debug("PUT /orgs/" + orgid + "/users/" + username + " result: " + n)
              if (n.asInstanceOf[Int] > 0) {
                AuthCache.putUserAndIsAdmin(compositeId, hashedPw, reqBody.password, reqBody.admin)
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.updated.successfully")))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
              }
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.updated", t.toString))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{orgid}/users/{username} ===============================
  @PATCH
  @Path("{username}")
  @Operation(summary = "Updates 1 attribute of a user", description = "Updates 1 attribute of an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes:", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "password": "abc",       // the user password this new user should have
  "admin": false,          // if true, this user will have full privilege within the organization
  "email": "me@gmail.com"  // contact email address for this user
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
  def userPatchRoute: Route = (path("orgs" / Segment / "users" / Segment) & patch & entity(as[PatchUsersRequest])) { (orgid, username, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem(ident, orgid, compositeId)) {
        complete({
          val updatedBy: String = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
          val hashedPw: String = if (reqBody.password.isDefined) Password.hash(reqBody.password.get) else "" // hash the pw if that is what is being updated
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid, updatedBy, hashedPw)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.agbot.attr.specified")))
          db.run(action.transactionally.asTry).map({
            case Success(n) =>
              logger.debug("PATCH /orgs/" + orgid + "/users/" + username + " result: " + n)
              if (n.asInstanceOf[Int] > 0) {
                if (reqBody.password.isDefined) AuthCache.putUser(compositeId, hashedPw, reqBody.password.get)
                if (reqBody.admin.isDefined) AuthCache.putUserIsAdmin(compositeId, reqBody.admin.get)
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.attr.updated", attrName, compositeId)))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
              }
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.updated", t.toString))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/users/{username} ===============================
  @DELETE
  @Path("{username}")
  @Operation(summary = "Deletes a user", description = "Deletes a user and all of its nodes and agbots. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userDeleteRoute: Route = (path("orgs" / Segment / "users" / Segment) & delete) { (orgid, username) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/users/$username")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.WRITE) { ident =>
      validate(orgid+"/"+username != Role.superUser, ExchMsg.translate("cannot.delete.root.user")) {
        complete({
          // Note: remove does *not* throw an exception if the key does not exist
          //todo: if ident.isHubAdmin then 1st get the target user row to verify it isn't a regular user
          db.run(UsersTQ.getUser(compositeId).delete.transactionally.asTry).map({
            case Success(v) => // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/users/$username result: $v")
              if (v > 0) {
                AuthCache.removeUser(compositeId) // these do not throw an error if the user doesn't exist
                //IbmCloudAuth.removeUserKey(compositeId) //todo: <- doesn't work because the IAM cache key includes the api key, which we don't know at this point. Address this in https://github.com/open-horizon/exchange-api/issues/232
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.deleted")))
              } else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.not.deleted", compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.deleted", compositeId, t.toString)))
          })
        }) // end of complete
      }
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/users/{username}/confirm ===============================
  @POST
  @Path("{username}/confirm")
  @Operation(summary = "Confirms if this username/password is valid", description = "Confirms whether or not this username exists and has the specified password. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "post ok"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userConfirmRoute: Route = (path("orgs" / Segment / "users" / Segment / "confirm") & post) { (orgid, username) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username/confirm")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.READ) { _ =>
      complete({
        // if we get here, the user/pw has been confirmed
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("confirmation.successful")))
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/users/{username}/changepw ===============================
  @POST
  @Path("{username}/changepw")
  @Operation(
    summary = "Changes the user's password",
    description = "Changes the user's password. Only the user itself, root, or a user with admin privilege can update an existing user's password.",
    parameters = Array(
      new Parameter(
        name = "orgid",
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
  "newPassword": "abc"  // the user password this user should have
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
  def userChangePwRoute: Route = (path("orgs" / Segment / "users" / Segment / "changepw") & post & entity(as[ChangePwRequest])) { (orgid, username, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId: String = OrgAndId(orgid, username).toString
    exchAuth(TUser(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val hashedPw: String = Password.hash(reqBody.newPassword)
          val action = reqBody.getDbUpdate(compositeId, orgid, hashedPw)
          db.run(action.transactionally.asTry).map({
            case Success(n) =>
              logger.debug("POST /orgs/" + orgid + "/users/" + username + "/changepw result: " + n)
              if (n.asInstanceOf[Int] > 0) {
                AuthCache.putUser(compositeId, hashedPw, reqBody.newPassword)
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("password.updated.successfully")))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
              }
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("user.password.not.updated", compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.password.not.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

}
