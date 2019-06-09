/** Services routes for all of the /users api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._

//====== These are the input and output structures for /users routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /users */
case class GetUsersResponse(users: Map[String,User], lastIndex: Int)

/** Input format for PUT /users/<username> */
case class PostPutUsersRequest(password: String, admin: Boolean, email: String)

case class PatchUsersRequest(password: Option[String], admin: Option[Boolean], email: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the user, and the attribute name being updated. */
  def getDbUpdate(username: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    password match {
      case Some(password2) => if (password2 == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the password can not be set to the empty string"))
        println("password2="+password2+".")
        val pw = if (Password.isHashed(password2)) password2 else Password.hash(password2)
        return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.password,u.lastUpdated)).update((username, pw, lastUpdated)), "password")
      case _ => ;
    }
    admin match { case Some(admin2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.admin,u.lastUpdated)).update((username, admin2, lastUpdated)), "admin"); case _ => ; }
    email match { case Some(email2) => return ((for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.email,u.lastUpdated)).update((username, email2, lastUpdated)), "email"); case _ => ; }
    return (null, null)
  }
}

//case class ResetPwResponse(token: String)

case class ChangePwRequest(newPassword: String) {

  def getDbUpdate(username: String, orgid: String): DBIO[_] = {
    val lastUpdated = ApiTime.nowUTC
    if (newPassword == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the password can not be set to the empty string"))
    val pw = if (Password.isHashed(newPassword)) newPassword else Password.hash(newPassword)
    return (for { u <- UsersTQ.rows if u.username === username } yield (u.username,u.password,u.lastUpdated)).update((username, pw, lastUpdated))
  }
}

/** Implementation for all of the /users routes */
trait UsersRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/users ================================ */
  val getUsers =
    (apiOperation[GetUsersResponse]("getUsers")
      summary("Returns all users")
      description("""Returns all users in the exchange DB. Can only be run by the root user.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/users", operation(getUsers)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TUser(OrgAndId(orgid,"*").toString),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    db.run(UsersTQ.getAllUsers(orgid).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/users result size: "+list.size)
      val users = new MutableHashMap[String, User]
      if (list.nonEmpty) for (e <- list) {
          val pw = if (superUser) e.password else StrConstants.hiddenPw
          users.put(e.username, User(pw, e.admin, e.email, e.lastUpdated))
        }
      if (users.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetUsersResponse(users.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/users/{username} ================================ */
  val getOneUser =
    (apiOperation[GetUsersResponse]("getOneUser")
      summary("Returns a user")
      description("""Returns the user with the specified username in the exchange DB. Can only be run by that user or root.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user."), paramType=ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/users/:username", operation(getOneUser)) ({
    val orgid = params("orgid")
    var username = params("username")
    var compositeId = OrgAndId(orgid,username).toString
    val ident = authenticate().authorizeTo(TUser(compositeId),Access.READ)
    if (username == "iamapikey" || username == "iamtoken") {
      // Need to change the target into the username that the key resolved to
      username = ident.getIdentity
      compositeId = OrgAndId(ident.getOrg,ident.getIdentity).toString
    }
    logger.debug("GET /orgs/"+orgid+"/users/"+username+" ident: "+ident)
    val superUser = ident.isSuperUser
    val resp = response     // needed so the db.run() future has this context
    // logger.debug("using postgres")
    db.run(UsersTQ.getUser(compositeId).result).map({ xs =>
      logger.debug("GET /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
      if (xs.nonEmpty) {
        val pw = if (superUser) xs.head.password else StrConstants.hiddenPw
        val user = User(pw, xs.head.admin, xs.head.email, xs.head.lastUpdated)
        val users = HashMap[String,User](xs.head.username -> user)
        resp.setStatus(HttpCode.OK)
        GetUsersResponse(users, 0)
      } else {      // not found
        // throw new IllegalArgumentException(username+" not found")    // do this if using onFailure
        resp.setStatus(HttpCode.NOT_FOUND)
        GetUsersResponse(HashMap[String,User](), 0)
      }
    })
  })

  // =========== POST /orgs/{orgid}/users/{username} ===============================
  val postUsers =
    (apiOperation[ApiResponse]("postUsers")
      summary "Adds a user"
      description """Creates a new user in the exchange DB. This can be run root/root, or a user with admin privilege. If run anonymously, it can create a user in the 'public' org. Note: this REST API method is limited in terms of how many times it can be run from the same source IP in a single day. The **request body** structure:

```
{
  "password": "abc",       // the user password this new user should have
  "admin": false,         // if true, this user will have full privilege within the organization
  "email": "me@gmail.com"         // contact email address for this user
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be added."), paramType = ParamType.Path),
        Parameter("body", DataType[PostPutUsersRequest],
          Option[String]("User object that needs to be added to the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postUsers2 = (apiOperation[PostPutUsersRequest]("postUsers2") summary("a") description("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/users/:username", operation(postUsers)) ({
    // Note: we do not currently verify this is a real person creating this (with, for example, captcha), so instead haproxy restricts the number of times a single IP address can call this in a day to a small number
    // Note: if this is invoked by anonymous, the ACLs will only succeed if the org is "public", because that is anonymous' org by default.
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    val ident = authenticate(anonymousOk = true).authorizeTo(TUser(compositeId),Access.CREATE)
    val user = try { parse(request.body).extract[PostPutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    val owner = if (user.admin) "admin" else ""
    val resp = response
    if (user.password == "" || user.email == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
    if (ident.isAnonymous && user.admin) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "an anonymous client can not create a user with admin authority"))
    db.run(UserRow(compositeId, orgid, user.password, user.admin, user.email, ApiTime.nowUTC).insertUser().asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
      xs match {
        case Success(v) => AuthCache.users.putBoth(Creds(compositeId, user.password), owner)    // the password passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, v+" user added successfully")
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)     // this usually happens if the user already exists
          ApiResponse(ApiResponseType.BAD_INPUT, "user not added: "+t.toString)
      }
    })
  })

  // =========== PUT /orgs/{orgid}/users/{username} ===============================
  val putUsers =
    (apiOperation[ApiResponse]("putUsers")
      summary "Adds/updates a user"
      description """Updates an existing user. Only the user itself or root can update an existing user. If run with root credentials this REST API method can also be used to create new users."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be updated."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutUsersRequest],
          Option[String]("User object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putUsers2 = (apiOperation[PostPutUsersRequest]("putUsers2") summary("a") description("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/users/:username", operation(putUsers)) ({
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    val ident = authenticate().authorizeTo(TUser(compositeId),Access.WRITE)
    val isRoot = ident.isSuperUser
    val user = try { parse(request.body).extract[PostPutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    val resp = response
    if (isRoot) {     // update or create of a (usually non-root) user by root
      //if (user.password == "" || (user.email == "" && !Role.isSuperUser(username))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
      if (user.password == "" || (user.email == "")) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
      db.run(UserRow(compositeId, orgid, user.password, user.admin, user.email, ApiTime.nowUTC).upsertUser.asTry).map({ xs =>
        logger.debug("PUT /orgs/"+orgid+"/users/"+username+" (root) result: "+xs.toString)
        xs match {
          case Success(v) => AuthCache.users.put(Creds(compositeId, user.password))    // the password passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, v+" user added or updated successfully")
          case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "user not added or updated: "+t.toString)
        }
      })
    } else {      // update by existing user
      if (user.admin && !ident.isAdmin) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "a user without admin privilege can not give admin privilege")) // ensure that a user can't elevate himself to an admin user
      db.run(UserRow(compositeId, orgid, user.password, user.admin, user.email, ApiTime.nowUTC).updateUser()).map({ xs =>     // updateUser() handles the case where pw or email is blank (i.e. do not update those fields)
        logger.debug("PUT /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
        try {
          val numUpdated = xs.toString.toInt
          if (numUpdated > 0) {
            if (user.password != "") AuthCache.users.put(Creds(compositeId, user.password))    // the password passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "user updated successfully")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "user '"+compositeId+"' not found")
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from user update: "+e) }    // the specific exception is NumberFormatException
      })
    }
  })

  // =========== PATCH /orgs/{orgid}/users/{username} ===============================
  val patchUsers =
    (apiOperation[ApiResponse]("patchUsers")
      summary "Updates 1 attribute of a user"
      description """Updates 1 attribute of an existing user. Only the user itself or root can update an existing user."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be updated."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchUsersRequest],
          Option[String]("User object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchUsers2 = (apiOperation[PatchUsersRequest]("patchUsers2") summary("a") description("a"))  // for some bizarre reason, the PatchUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/users/:username", operation(patchUsers)) ({
    // Note: we currently do not have a way to verify this is a real person creating this, so we use rate limiting in haproxy
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    val ident = authenticate().authorizeTo(TUser(compositeId),Access.WRITE)
    val user = try { parse(request.body).extract[PatchUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    val resp = response
    val (action, attrName) = user.getDbUpdate(compositeId, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid agbot attribute specified"))
    if (attrName == "admin" && user.admin.getOrElse(false) && !ident.isAdmin) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "a user without admin privilege can not give admin privilege")) // ensure that a user can't elevate himself to an admin user
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
          val numUpdated = v.toString.toInt     // v comes to us as type Any
          if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
            user.password match { case Some(pw) if (pw != "") => AuthCache.users.put(Creds(compositeId, pw)); case _ => ; }    // the password passed in to the cache should be the non-hashed one. We do not need to run putOwner because patch does not change the owner
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of user '"+compositeId+"' updated")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "user '"+compositeId+"' not found")
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "user '"+compositeId+"' not inserted or updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/users/{username} ===============================
  val deleteUsers =
    (apiOperation[ApiResponse]("deleteUsers")
      summary "Deletes a user"
      description "Deletes a user from the exchange DB and all of its nodes and agbots. Can only be run by that user or root."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be deleted."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/users/:username", operation(deleteUsers)) ({
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    authenticate().authorizeTo(TUser(compositeId),Access.WRITE)
    val resp = response
    // now with all the foreign keys set up correctly and onDelete=cascade, the db will automatically delete the associated rows in other tables
    db.run(UsersTQ.getUser(compositeId).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /users/"+username+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.users.removeBoth(compositeId)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "user deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "user '"+compositeId+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "user '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  // =========== POST /orgs/{orgid}/users/{username}/confirm ===============================
  val postUsersConfirm =
    (apiOperation[ApiResponse]("postUsersConfirm")
      summary "Confirms if this username/password is valid"
      description "Confirms whether or not this username exists and has the specified password. Can only be run by that user or root."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be confirmed."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  post("/orgs/:orgid/users/:username/confirm", operation(postUsersConfirm)) ({
    // Note: the haproxy rate limiting guards against pw cracking attempts
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    authenticate().authorizeTo(TUser(compositeId),Access.READ)
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "confirmation successful")
  })

  /* Reset as anonymous does not work with orgs...
  // =========== POST /orgs/{orgid}/users/{username}/reset ===============================
  val postUsersReset =
    (apiOperation[ApiResponse]("postUsersReset")
      summary "Emails the user a token for resetting their password"
      description """Use this if you have forgotten your password. (If you know your password and want to change it, you can use PUT /orgs/{orgid}/users/{username}.) Emails the user a timed token that can be given to POST /orgs/{orgid}/users/{username}/changepw. The token is good for 10 minutes. In the special case in which root's credentials are specified in the HTTP header, the token will not be emailed, instead it be returned in the response like this:

```
{
  "token": "<token>"
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be reset."), paramType = ParamType.Path)
        // Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  post("/orgs/:orgid/users/:username/reset", operation(postUsersReset)) ({
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    // Note: anonymous is allowed, obviously, but haproxy rate limiting is used to prevent someone else flooding their email
    val ident = authenticate(anonymousOk = true).authorizeTo(TUser(compositeId),Access.RESET_USER_PW)

    if (ident.isSuperUser) {
      // verify the username exists via the cache
      AuthCache.users.get(compositeId) match {
        case Some(_) => ;      // do not need to do anything
        case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "username '"+compositeId+"' not found"))
      }
      status_=(HttpCode.POST_OK)
      ResetPwResponse(createToken(compositeId))
    }
    else {
      // need the user's email to send him the reset token
      val resp = response

      // Form swagger changepw url
      logger.trace("X-API-Request: "+request.header("X-API-Request"))
      val requestUrl = request.header("X-API-Request") match {
        // Staging or prod environment, Haproxy will pass header X-API-Request -> https://exchange.staging.bluehorizon.network/api or https://exchange.bluehorizon.network/api
        case Some(url) => url
        // Local development environment, we get http://localhost:8080/api/v1/users/{user}/reset
        case None => logger.trace("request.uri: "+request.uri)
          val R = """^(.*)/v\d+/orgs/[^/]* /users/[^/]* /reset$""".r   <- had to separate the * and /
          request.uri.toString match {
            case R(url) => url
            case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "unexpected uri"))
          }
      }
      val changePwUrl = requestUrl+"/api?url="+requestUrl+"/api-docs#!/v1/postUsersChangePw"
      logger.trace("changePwUrl: "+changePwUrl)

      //note: this is broken, get: java.lang.NoClassDefFoundError: com/sun/mail/util/PropUtil
      db.run(UsersTQ.getEmail(compositeId).result).map({ xs =>
        logger.debug("POST /orgs/"+orgid+"/users/"+username+"/reset result: "+xs.toString)
        if (xs.nonEmpty) {
          val email = xs.head
          logger.debug("Emailing reset token for user "+compositeId+" to email: "+email)
          //Note: this used Email.scala, which was removed from our git on 6/9/19
          Email.send(compositeId, email, createToken(compositeId), changePwUrl) match {
            case Success(msg) => resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, msg)
            case Failure(e) => resp.setStatus(HttpCode.BAD_INPUT)
              ApiResponse(ApiResponseType.BAD_INPUT, e.toString)
          }
        } else {      // username not found in db
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "username "+compositeId+"' not found")
        }
      })
    }
  })
  */

  // =========== POST /orgs/{orgid}/users/{username}/changepw ===============================
  val postUsersChangePw =
    (apiOperation[ApiResponse]("postUsersChangePw")
      summary "Changes the user's password using a reset token for authentication"
      description "Use POST /orgs/{orgid}/users/{username}/reset to have a timed token sent to your email address. Then give that token and your new password to this REST API method."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be reset."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Reset token obtained from POST /orgs/{orgid}/users/{username}/reset. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[ChangePwRequest],
          Option[String]("Your new password."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postUsersChangePw2 = (apiOperation[ChangePwRequest]("postUsersChangePw2") summary("a") description("a"))

  post("/orgs/:orgid/users/:username/changepw", operation(postUsersChangePw)) ({
    val orgid = params("orgid")
    val username = params("username")
    val compositeId = OrgAndId(orgid,username).toString
    authenticate(hint = "token").authorizeTo(TUser(compositeId),Access.WRITE)
    val req = try { parse(request.body).extract[ChangePwRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    val action = req.getDbUpdate(compositeId, orgid)
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/users/"+username+"/changepw result: "+xs.toString)
      xs match {
        case Success(v) => try {
          val numUpdated = v.toString.toInt     // v comes to us as type Any
          if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.users.put(Creds(compositeId, req.newPassword))    // the password passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "password updated successfully")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "user '"+compositeId+"' not found")
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "user '"+compositeId+"' password not updated: "+t.toString)
      }
    })
  })

}
