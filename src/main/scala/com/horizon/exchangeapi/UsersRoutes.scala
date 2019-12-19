/** Services routes for all of the /users api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
import io.swagger.v3.oas.annotations._
import com.horizon.exchangeapi.tables._
import org.json4s._
//import org.json4s.jackson.JsonMethods._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.util._
import scala.concurrent.ExecutionContext.Implicits.global

//====== These are the input and output structures for /users routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /users */
final case class GetUsersResponse(users: Map[String, User], lastIndex: Int)

/** Input format for PUT /users/<username> */
final case class PostPutUsersRequest(password: String, admin: Boolean, email: String) {
  def getAnyProblem(identIsAdmin: Boolean): Option[String] = {
    if (password == "" || email == "") Some(ExchMsg.translate("password.and.email.must.be.non.blank.when.creating.user"))
    else if (admin && !identIsAdmin) Some(ExchMsg.translate("non.admin.user.cannot.make.admin.user")) // ensure that a user can't elevate himself to an admin user
    else None // None means no problems with input
  }
}

final case class PatchUsersRequest(password: Option[String], admin: Option[Boolean], email: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(identIsAdmin: Boolean): Option[String] = { //todo: use
    if (password.isDefined && password.get == "") Some(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    else if (admin.isDefined && admin.get && !identIsAdmin) Some(ExchMsg.translate("non.admin.user.cannot.make.admin.user")) // ensure that a user can't elevate himself to an admin user
    else None // None means no problems with input
  }

  /** Returns a tuple of the db action to update parts of the user, and the attribute name being updated. */
  def getDbUpdate(username: String, orgid: String, updatedBy: String, hashedPw: String): (DBIO[_], String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    password match {
      case Some(_) =>
        //val pw = if (Password.isHashed(password2)) password2 else Password.hash(password2)
        return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.password, u.lastUpdated, u.updatedBy)).update((username, hashedPw, lastUpdated, updatedBy)), "password")
      case _ => ;
    }
    admin match { case Some(admin2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.admin, u.lastUpdated, u.updatedBy)).update((username, admin2, lastUpdated, updatedBy)), "admin"); case _ => ; }
    email match { case Some(email2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.email, u.lastUpdated, u.updatedBy)).update((username, email2, lastUpdated, updatedBy)), "email"); case _ => ; }
    return (null, null)
  }
}

final case class ChangePwRequest(newPassword: String) {
  def getAnyProblem: Option[String] = { //todo: use
    if (newPassword == "") Some(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    else None // None means no problems with input
  }

  def getDbUpdate(username: String, orgid: String, hashedPw: String): DBIO[_] = {
    val lastUpdated = ApiTime.nowUTC
    //val pw = if (Password.isHashed(newPassword)) newPassword else Password.hash(newPassword)
    return (for { u <- UsersTQ.rows if u.username === username } yield (u.username, u.password, u.lastUpdated)).update((username, hashedPw, lastUpdated))
  }
}

/** Implementation for all of the /users routes */
@Path("/v1/orgs/{orgid}/users")
class UsersRoutes(implicit val system: ActorSystem) extends JacksonSupport with AuthenticationSupport {
  def db: Database = ExchangeApiApp.getDb
  lazy implicit val logger: LoggingAdapter = Logging(system, classOf[OrgsRoutes])
  //protected implicit def jsonFormats: Formats

  def routes: Route = usersGetRoute ~ userGetRoute ~ userPostRoute ~ userPutRoute ~ userPatchRoute ~ userDeleteRoute ~ userConfirmRoute ~ userChangePwRoute

  /* ====== GET /orgs/{orgid}/users ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all users", description = """Returns all users. Can only be run by the root user.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetUsersResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def usersGetRoute: Route = (get & path("orgs" / Segment / "users") & extractCredentials) { (orgid, creds) =>
    logger.debug(s"Doing GET /orgs/$orgid/users")
    auth(creds, TUser(OrgAndId(orgid, "*").toString), Access.READ) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(ident) =>
        complete({
          logger.debug(s"GET /orgs/$orgid/users identity: $ident")
          db.run(UsersTQ.getAllUsers(orgid).result).map({ list =>
            logger.debug(s"GET /orgs/$orgid/users result size: ${list.size}")
            val users = list.map(e => e.username -> User(if (ident.isSuperUser) e.hashedPw else StrConstants.hiddenPw, e.admin, e.email, e.lastUpdated, e.updatedBy)).toMap
            val code = if (users.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetUsersResponse(users, 0))
          })
        }) // end of complete
    } // end of auth match
  }

  /* ====== GET /orgs/{orgid}/users/{username} ================================ */
  @GET
  @Path("{username}")
  @Operation(summary = "Returns a user", description = """Returns the specified username. Can only be run by that user or root.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetUsersResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userGetRoute: Route = (get & path("orgs" / Segment / "users" / Segment) & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing GET /orgs/$orgid/users/$username")
    var compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.READ) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(ident) =>
        complete({
          logger.debug(s"GET /orgs/$orgid/users/$username identity: $ident")
          var realUsername = username
          if (username == "iamapikey" || username == "iamtoken") {
            // Need to change the target into the username that the key resolved to
            realUsername = ident.getIdentity
            compositeId = OrgAndId(ident.getOrg, ident.getIdentity).toString
          }
          db.run(UsersTQ.getUser(compositeId).result).map({ list =>
            logger.debug(s"GET /orgs/$orgid/users/$realUsername result size: ${list.size}")
            val users = list.map(e => e.username -> User(if (ident.isSuperUser) e.hashedPw else StrConstants.hiddenPw, e.admin, e.email, e.lastUpdated, e.updatedBy)).toMap
            val code = if (users.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetUsersResponse(users, 0))
          })
        }) // end of complete
    } // end of auth match
  }

  // =========== POST /orgs/{orgid}/users/{username} ===============================
  @POST
  @Path("{username}")
  @Operation(summary = "Adds a user", description = """Creates a new user. This can be run root/root, or a user with admin privilege.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    requestBody = new RequestBody(description = """
```
{
  "password": "abc",       // the user password this new user should have
  "admin": false,         // if true, this user will have full privilege within the organization
  "email": "me@gmail.com"         // contact email address for this user
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostPutUsersRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userPostRoute: Route = (post & path("orgs" / Segment / "users" / Segment) & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.CREATE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(ident) =>
        entity(as[PostPutUsersRequest]) { userReq =>
          validate(userReq.getAnyProblem(ident.isAdmin).isEmpty, "Problem in request body") { //todo: create a custom validation directive so we can return the specific error msg from getAnyProblem to the client
            complete({
              val updatedBy = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
              val hashedPw = Password.hash(userReq.password)
              db.run(UserRow(compositeId, orgid, hashedPw, userReq.admin, userReq.email, ApiTime.nowUTC, updatedBy).insertUser().asTry).map({ xs =>
                logger.debug("POST /orgs/" + orgid + "/users/" + username + " result: " + xs.toString)
                xs match {
                  case Success(v) =>
                    AuthCache.putUserAndIsAdmin(compositeId, hashedPw, userReq.password, userReq.admin)
                    (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.added.successfully", v)))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.added", t.toString)))
                }
              })
            }) // end of complete
          } // end of validate
        } // end of entity
    } // end of auth match
  }

  // =========== PUT /orgs/{orgid}/users/{username} ===============================
  @PUT
  @Path("{username}")
  @Operation(summary = "Updates a user", description = """Updates an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostPutUsersRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userPutRoute: Route = (put & path("orgs" / Segment / "users" / Segment) & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.WRITE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(ident) =>
        entity(as[PostPutUsersRequest]) { userReq =>
          validate(userReq.getAnyProblem(ident.isAdmin).isEmpty, "Problem in request body") { //todo: create a custom validation directive so we can return the specific error msg from getAnyProblem to the client
            complete({
              val updatedBy = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
              val hashedPw = Password.hash(userReq.password)
              db.run(UserRow(compositeId, orgid, hashedPw, userReq.admin, userReq.email, ApiTime.nowUTC, updatedBy).updateUser().asTry).map({ xs =>
                logger.debug("PUT /orgs/" + orgid + "/users/" + username + " result: " + xs.toString)
                xs match {
                  case Success(n) =>
                    if (n.asInstanceOf[Int] > 0) {
                      AuthCache.putUserAndIsAdmin(compositeId, hashedPw, userReq.password, userReq.admin)
                      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.updated.successfully")))
                    } else {
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
                    }
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
                }
              })
            }) // end of complete
          } // end of validate
        } // end of entity
    } // end of auth match
  }

  // =========== PATCH /orgs/{orgid}/users/{username} ===============================
  @PATCH
  @Path("{username}")
  @Operation(summary = "Updates 1 attribute of a user", description = """Updates 1 attribute of an existing user. Only the user itself, root, or a user with admin privilege can update an existing user.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes:", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PatchUsersRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userPatchRoute: Route = (patch & path("orgs" / Segment / "users" / Segment) & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.WRITE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(ident) =>
        entity(as[PatchUsersRequest]) { userReq =>
          validate(userReq.getAnyProblem(ident.isAdmin).isEmpty, "Problem in request body") { //todo: create a custom validation directive so we can return the specific error msg from getAnyProblem to the client
            complete({
              val updatedBy = ident match { case IUser(identCreds) => identCreds.id; case _ => "" }
              val hashedPw = if (userReq.password.isDefined) Password.hash(userReq.password.get) else "" // hash the pw if that is what is being updated
              val (action, attrName) = userReq.getDbUpdate(compositeId, orgid, updatedBy, hashedPw)
              if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.agbot.attr.specified")))
              else db.run(action.transactionally.asTry).map({ xs =>
                logger.debug("PATCH /orgs/" + orgid + "/users/" + username + " result: " + xs.toString)
                xs match {
                  case Success(n) =>
                    if (n.asInstanceOf[Int] > 0) {
                      if (userReq.password.isDefined) AuthCache.putUser(compositeId, hashedPw, userReq.password.get)
                      if (userReq.admin.isDefined) AuthCache.putUserIsAdmin(compositeId, userReq.admin.get)
                      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.attr.updated", attrName, compositeId)))
                    } else {
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
                    }
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.not.updated", t.toString)))
                }
              })
            }) // end of complete
          } // end of validate
        } // end of entity
    } // end of auth match
  }

  // =========== DELETE /orgs/{orgid}/users/{username} ===============================
  @DELETE
  @Path("{username}")
  @Operation(summary = "Deletes an user", description = """Deletes a user and all of its nodes and agbots. This can only be called by root or a user in the org with the admin role.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userDeleteRoute: Route = (delete & path("orgs" / Segment / "users" / Segment) & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/users/$username")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.WRITE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          // remove does *not* throw an exception if the key does not exist
          db.run(UsersTQ.getUser(compositeId).delete.transactionally.asTry).map({ xs =>
            logger.debug(s"DELETE /orgs/$orgid/users/$username result: " + xs.toString)
            xs match {
              case Success(v) => // there were no db errors, but determine if it actually found it or not
                if (v > 0) {
                  AuthCache.removeUserAndIsAdmin(compositeId)
                  (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("user.deleted")))
                } else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("user.not.deleted", compositeId, t.toString)))
            }
          })
        }) // end of complete
    } // end of auth match
  }

  // =========== POST /orgs/{orgid}/users/{username}/confirm ===============================
  @POST
  @Path("{username}/confirm")
  @Operation(summary = "Confirms if this username/password is valid", description = """Confirms whether or not this username exists and has the specified password. This can only be called by root or a user in the org with the admin role.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "post ok"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userConfirmRoute: Route = (post & path("orgs" / Segment / "users" / Segment / "confirm") & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username/confirm")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.READ) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          // if we get here, the user/pw has been confirmed
          (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("confirmation.successful")))
        }) // end of complete
    } // end of auth match
  }

  // =========== POST /orgs/{orgid}/users/{username}/changepw ===============================
  @POST
  @Path("{username}/changepw")
  @Operation(summary = "Changes the user's password", description = """Changes the user's password. Only the user itself, root, or a user with admin privilege can update an existing user's password.""",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "username", in = ParameterIn.PATH, description = "Username (orgid/username) of the user.")),
    requestBody = new RequestBody(description = """
```
{
  "newPassword": "abc"       // the user password this user should have
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[ChangePwRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "password updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def userChangePwRoute: Route = (post & path("orgs" / Segment / "users" / Segment / "changepw") & extractCredentials) { (orgid, username, creds) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$username")
    val compositeId = OrgAndId(orgid, username).toString
    auth(creds, TUser(compositeId), Access.WRITE) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        entity(as[ChangePwRequest]) { req =>
          validate(req.getAnyProblem.isEmpty, "Problem in request body") { //todo: create a custom validation directive so we can return the specific error msg from getAnyProblem to the client
            complete({
              val hashedPw = Password.hash(req.newPassword)
              val action = req.getDbUpdate(compositeId, orgid, hashedPw)
              db.run(action.transactionally.asTry).map({ xs =>
                logger.debug("POST /orgs/" + orgid + "/users/" + username + "/changepw result: " + xs.toString)
                xs match {
                  case Success(n) =>
                    if (n.asInstanceOf[Int] > 0) {
                      AuthCache.putUser(compositeId, hashedPw, req.newPassword)
                      (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("password.updated.successfully")))
                    } else {
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", compositeId)))
                    }
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("user.password.not.updated", compositeId, t.toString)))
                }
              })
            }) // end of complete
          } // end of validate
        } // end of entity
    } // end of auth match
  }

}
