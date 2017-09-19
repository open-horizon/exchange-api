/** Services routes for all of the /users api methods. */
package com.horizon.exchangeapi

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._
import com.horizon.exchangeapi.tables._

import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._

//====== These are the input and output structures for /users routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /users */
case class GetUsersResponse(users: Map[String,User], lastIndex: Int)

/** Input format for PUT /users/<username> */
case class PutUsersRequest(password: String, email: String)

case class ResetPwResponse(token: String)

case class ChangePwRequest(newPassword: String)

/** Implementation for all of the /users routes */
trait UsersRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/users ================================ */
  val getUsers =
    (apiOperation[GetUsersResponse]("getUsers")
      summary("Returns all users")
      notes("""Returns all users in the exchange DB. Can only be run by the root user.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/users", operation(getUsers)) ({
    val orgid = swaggerHack("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TUser(OrgAndId(orgid,"*").toString),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    db.run(UsersTQ.getAllUsers(orgid).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/users result size: "+list.size)
      val users = new MutableHashMap[String, User]
      if (list.nonEmpty) for (e <- list) {
          val pw = if (superUser) e.password else StrConstants.hiddenPw
          users.put(e.username, User(pw, e.email, e.lastUpdated))
        }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetUsersResponse(users.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/users/{username} ================================ */
  val getOneUser =
    (apiOperation[GetUsersResponse]("getOneUser")
      summary("Returns a user")
      notes("""Returns the user with the specified username in the exchange DB. Can only be run by that user or root.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user."), paramType=ParamType.Query),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/users/:username", operation(getOneUser)) ({
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    val ident = credsAndLog().authenticate().authorizeTo(TUser(compositeId),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response     // needed so the db.run() future has this context
    // logger.debug("using postgres")
    db.run(UsersTQ.getUser(compositeId).result).map({ xs =>
      logger.debug("GET /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
      if (xs.nonEmpty) {
        val pw = if (superUser) xs.head.password else StrConstants.hiddenPw
        val user = User(pw, xs.head.email, xs.head.lastUpdated)
        val users = HashMap[String,User](xs.head.username -> user)
        GetUsersResponse(users, 0)
      } else {      // not found
        // throw new IllegalArgumentException(username+" not found")    // do this if using onFailure
        resp.setStatus(HttpCode.NOT_FOUND)
        GetUsersResponse(HashMap[String,User](), 0)
      }
    })
  })

  // =========== PUT /orgs/{orgid}/users/{username} ===============================
  val putUsers =
    (apiOperation[ApiResponse]("putUsers")
      summary "Adds/updates a user"
      notes """Updates an existing user. Only the user itself or root can update an existing user. If run with root credentials this REST API method can also be used to create new users. The **request body** structure:

```
{
  "password": "abc",       // user password, set by user when adding this user
  "email": "me@gmail.com"         // contact email address for this user
}
```"""
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be updated."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutUsersRequest],
          Option[String]("User object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putUsers2 = (apiOperation[PutUsersRequest]("putUsers2") summary("a") notes("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/users/:username", operation(putUsers)) ({
    // Note: we currently do not have a way to verify this is a real person creating this, so we use rate limiting in haproxy
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    val ident = credsAndLog().authenticate().authorizeTo(TUser(compositeId),Access.WRITE)
    val isRoot = ident.isSuperUser
    val user = try { parse(request.body).extract[PutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    val resp = response
    if (isRoot) {     // update or create of a (usually non-root) user by root
      //if (user.password == "" || (user.email == "" && !Role.isSuperUser(username))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
      if (user.password == "" || (user.email == "")) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
      db.run(UserRow(compositeId, orgid, user.password, user.email, ApiTime.nowUTC).upsertUser.asTry).map({ xs =>
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
      db.run(UserRow(compositeId, orgid, user.password, user.email, ApiTime.nowUTC).updateUser()).map({ xs =>     // updateUser() handles the case where pw or email is blank (i.e. do not update those fields)
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


  // =========== POST /orgs/{orgid}/users/{username} ===============================
  val postUsers =
    (apiOperation[ApiResponse]("postUsers")
      summary "Adds a user"
      notes """Adds a new user to the exchange DB. Note: this REST API method is severely limited in terms of how many times it can be run from the same source IP in a single day. The **request body** structure:

```
{
  "password": "abc",       // the user password this new user should have
  "email": "me@gmail.com"         // contact email address for this user
}
```"""
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be added."), paramType = ParamType.Path, required=false),
        Parameter("body", DataType[PutUsersRequest],
          Option[String]("User object that needs to be added to the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postUsers2 = (apiOperation[PutUsersRequest]("postUsers2") summary("a") notes("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/users/:username", operation(postUsers)) ({
    // Note: we do not currently verify this is a real person creating this (with, for example, captcha), because haproxy restricts the number of
    //      times a single IP address can call this in a day to a very small number
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    credsAndLog(true).authenticate().authorizeTo(TUser(compositeId),Access.CREATE)
    val user = try { parse(request.body).extract[PutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    val resp = response
    if (user.password == "" || user.email == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
    db.run(UserRow(compositeId, orgid, user.password, user.email, ApiTime.nowUTC).insertUser().asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/users/"+username+" result: "+xs.toString)
      xs match {
        case Success(v) => AuthCache.users.put(Creds(compositeId, user.password))    // the password passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, v+" user added successfully")
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)     // this usually happens if the user already exists
          ApiResponse(ApiResponseType.BAD_INPUT, "user not added: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/users/{username} ===============================
  val deleteUsers =
    (apiOperation[ApiResponse]("deleteUsers")
      summary "Deletes a user"
      notes "Deletes a user from the exchange DB and all of its nodes and agbots. Can only be run by that user or root."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be deleted."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/users/:username", operation(deleteUsers)) ({
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    credsAndLog().authenticate().authorizeTo(TUser(compositeId),Access.WRITE)
    val resp = response
    // now with all the foreign keys set up correctly and onDelete=cascade, the db will automatically delete the associated rows in other tables
    db.run(UsersTQ.getUser(compositeId).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /users/"+username+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.users.remove(compositeId)
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
      notes "Confirms whether or not this username exists and has the specified password. Can only be run by that user or root."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be confirmed."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  post("/orgs/:orgid/users/:username/confirm", operation(postUsersConfirm)) ({
    // Note: the haproxy rate limiting guards against pw cracking attempts
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    credsAndLog().authenticate().authorizeTo(TUser(compositeId),Access.READ)
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "confirmation successful")
  })

  // =========== POST /orgs/{orgid}/users/{username}/reset ===============================
  val postUsersReset =
    (apiOperation[ApiResponse]("postUsersReset")
      summary "Emails the user a token for resetting their password"
      notes """Use this if you have forgotten your password. (If you know your password and want to change it, you can use PUT /orgs/{orgid}/users/{username}.) Emails the user a timed token that can be given to POST /orgs/{orgid}/users/{username}/changepw. The token is good for 10 minutes. In the special case in which root's credentials are specified in the HTTP header, the token will not be emailed, instead it be returned in the response like this:

```
{
  "token": "<token>"
}
```"""
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be reset."), paramType = ParamType.Path)
        // Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  post("/orgs/:orgid/users/:username/reset", operation(postUsersReset)) ({
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    // Note: anonymous is allowed, obviously, but haproxy rate limiting is used to prevent someone else flooding their email
    val ident = credsAndLog(true).authenticate().authorizeTo(TUser(compositeId),Access.RESET_USER_PW)

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
          val R = """^(.*)/v\d+/users/[^/]*/reset$""".r
          request.uri.toString match {
            case R(url) => url
            case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "unexpected uri"))
          }
      }
      val changePwUrl = requestUrl+"/api?url="+requestUrl+"/api-docs#!/v1/postUsersChangePw"
      logger.trace("changePwUrl: "+changePwUrl)

      db.run(UsersTQ.getEmail(compositeId).result).map({ xs =>
        logger.debug("POST /orgs/"+orgid+"/users/"+username+"/reset result: "+xs.toString)
        if (xs.nonEmpty) {
          val email = xs.head
          logger.debug("Emailing reset token for user "+compositeId+" to email: "+email)
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

  // =========== POST /orgs/{orgid}/users/{username}/changepw ===============================
  val postUsersChangePw =
    (apiOperation[ApiResponse]("postUsersChangePw")
      summary "Changes the user's password using a reset token for authentication"
      notes "Use POST /orgs/{orgid}/users/{username}/reset to have a timed token sent to your email address. Then give that token and your new password to this REST API method."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username (orgid/username) of the user to be reset."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Reset token obtained from POST /orgs/{orgid}/users/{username}/reset. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[ChangePwRequest],
          Option[String]("Your new password."),
          paramType = ParamType.Body)
        )
      )
  val postUsersChangePw2 = (apiOperation[ChangePwRequest]("postUsersChangePw2") summary("a") notes("a"))

  post("/orgs/:orgid/users/:username/changepw", operation(postUsersChangePw)) ({
    val orgid = swaggerHack("orgid")
    val username = params("username")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,username).toString
    credsAndLog().authenticate("token").authorizeTo(TUser(compositeId),Access.WRITE)
    val req = try { parse(request.body).extract[ChangePwRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    db.run(UserRow(compositeId, orgid, req.newPassword, "", ApiTime.nowUTC).updateUser()).map({ xs =>     // updateUser() handles the case where pw or email is blank (i.e. do not update those fields)
      logger.debug("POST /orgs/"+orgid+"/users/"+username+"/changepw result: "+xs.toString)
      try {
        val numUpdated = xs.toString.toInt
        if (numUpdated > 0) {
          AuthCache.users.put(Creds(compositeId, req.newPassword))    // the password passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "password updated successfully")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "user '"+compositeId+"' not found")
        }
      } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from user update: "+e) }    // the specific exception is NumberFormatException
    })
  })

}
