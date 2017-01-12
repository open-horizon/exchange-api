/** Services routes for all of the /users api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatra.json._
import org.slf4j._
import Access._
import BaseAccess._
import scala.util._
import scala.util.control.Breaks._
import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import com.horizon.exchangeapi.tables._

//====== These are the input and output structures for /users routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /users */
case class GetUsersResponse(users: Map[String,User], lastIndex: Int)

/** Input format for PUT /users/<username> */
case class PutUsersRequest(password: String, email: String) {
  /** Puts this user into the db.
   * @param create This is just the caller telling us whether the put was intended to be a create (no credentials given) or an update (credentials given).
   *      I.e. when create==true only put in db if it does not exist, otherwise update entry only when it does exist.
   */
  def copyToTempDb(username: String, create: Boolean) = {
    // Note: pw can be blank if the user wants to update his email w/o changing his pw, and email blank when the changepw rest method calls this
    // println("copyToDb: "+username+", "+ password)
    if (create && (password == "" || (email == "" && !Role.isSuperUser(username)))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
    var pw = if (password == "") "" else if (Password.isHashed(password)) password else Password.hash(password)
    var mail = email
    TempDb.users.get(username) match {
      case Some(user) =>  if (create) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "user '"+username+"' already exists"))
        // otherwise update existing user, but do not update pw if specified one was blank
        if (password == "") pw = user.password    // do not need to hash it because it already is
        if (email == "") mail = user.email
      case None => if (!create) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "user '"+username+"' not found"))
    }
    TempDb.users.put(username, User(pw, mail, ApiTime.nowUTC))
    if (password != "") AuthCache.users.put(Creds(username, password))    // the password passed in to the cache should be the non-hashed one
  }
}

case class ResetPwResponse(token: String)

case class ChangePwRequest(newPassword: String)

/** Implementation for all of the /users routes */
trait UsersRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /users ================================ */
  val getUsers =
    (apiOperation[GetUsersResponse]("getUsers")
      summary("Returns all users")
      notes("""Returns all users in the exchange DB. Can only be run by the root user.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of exchange user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /users. Can only be called by the root user to see all users. */
  get("/users", operation(getUsers)) ({
    // logger.info("GET /users")
    val creds = validateUser(BaseAccess.READ, "*")
    val superUser = isSuperUser(creds)
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      // logger.debug("using mem db")
      var users = TempDb.users.toMap
      // println(users.toString)
      // if (superUser) users = users.mapValues(u => {val u2 = u.copy; u2.password = "********"; u2})
      if (!superUser) users = users.mapValues(u => u.hidePassword)
      GetUsersResponse(users, 0)
    } else {
      db.run(UsersTQ.rows.result).map({ list =>
        logger.debug("GET /users result size: "+list.size)
        val users = new MutableHashMap[String, User]
        for (e <- list) {
          val pw = if (superUser) e.password else StrConstants.hiddenPw
          users.put(e.username, User(pw, e.email, e.lastUpdated))
        }
        GetUsersResponse(users.toMap, 0)
      })
    }
  })

  /* ====== GET /users/{username} ================================ */
  val getOneUser =
    (apiOperation[GetUsersResponse]("getOneUser")
      summary("Returns a user")
      notes("""Returns the user with the specified username in the exchange DB. Can only be run by that user or root.

**Notes about the response format:**

- **The format may change in the future.**
- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of the user."), paramType=ParamType.Query),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /users/{username}. Normally called by the user to verify his own entry after a reboot. */
  get("/users/:username", operation(getOneUser)) ({
    // logger.info("GET /users/"+params("username"))
    val username = if (params("username") == "{username}") swaggerHack("username") else params("username")
    val creds = validateUser(BaseAccess.READ, username)
    val superUser = isSuperUser(creds)
    val resp = response     // needed so the db.run() future has this context
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      // logger.debug("using memory DB")
      var users = TempDb.users.toMap.filter(u => u._1 == username)
      if (!superUser) users = users.mapValues(u => u.hidePassword)
      if (!users.contains(username)) status_=(HttpCode.NOT_FOUND)
      GetUsersResponse(users, 0)
    } else {
      // logger.debug("using postgres")
      db.run(UsersTQ.getUser(username).result).map({ xs =>
        logger.debug("GET /users/"+username+" result: "+xs.toString)
        // logger.debug("size: "+xs.size)
        if (xs.size > 0) {
          val pw = if (superUser) xs.head.password else StrConstants.hiddenPw
          val user = User(pw, xs.head.email, xs.head.lastUpdated)
          // val users = new MutableHashMap[String,User]() += ((xs.head._1, user))
          val users = HashMap[String,User](xs.head.username -> user)
          GetUsersResponse(users, 0)
        } else {      // not found
          // logger.debug(username+" not found")
          // throw new IllegalArgumentException(username+" not found")    // do this if using onFailure
          resp.setStatus(HttpCode.NOT_FOUND)
          GetUsersResponse(HashMap[String,User](), 0)
        }
      })
      /* onComplete, onSuccess, and onFailure return Unit (nothing), so are not useful for getting ouput back to the rest client...
      f onComplete {
        case Success(result) => logger.debug("GET /users/{username} success, result: "+result.toString); result
        case Failure(t) => logger.debug("GET /users/{username} failure: " + t.getMessage)
          status_=(HttpCode.NOT_FOUND)
          GetUsersResponse(HashMap[String,User](), 0)
      }
      f onFailure {
        case e: IllegalArgumentException => logger.debug("onFailure for IllegalArgumentException")
          halt(HttpCode.NOT_FOUND, "not found")
      }
      */
    }
  })

  // =========== PUT /users/{username} ===============================
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
        Parameter("username", DataType.String, Option[String]("Username of the user to be updated."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutUsersRequest],
          Option[String]("User object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putUsers2 = (apiOperation[PutUsersRequest]("putUsers2") summary("a") notes("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /user/{username}. Must be called by root to add the user, or called by user to update itself. */
  put("/users/:username", operation(putUsers)) ({
    // logger.info("PUT /users/"+params("username"))
    //TODO: verify this is a real person creating this
    val username = params("username")
    // val create = isAnonymous(credentials(true))
    // if (create) validateUser(BaseAccess.CREATE, username)
    // else validateUser(BaseAccess.WRITE, username)
    val creds = validateUser(BaseAccess.WRITE, username)
    val isRoot = Role.isSuperUser(creds.id)
    val user = try { parse(request.body).extract[PutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      user.copyToTempDb(username, isRoot)     // this method handles the create or update logic
      status_=(HttpCode.PUT_OK)
      ApiResponse(ApiResponseType.OK, "user added to or updated in the exchange")
    } else {
      val resp = response
      if (isRoot) {     // update or create of a (usually non-root) user by root
        if (user.password == "" || (user.email == "" && !Role.isSuperUser(username))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
        // if (user.password == "" || user.email == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
        db.run(UserRow(username, user.password, user.email, ApiTime.nowUTC).upsertUser.asTry).map({ xs =>
          logger.debug("PUT /users/"+username+" (root) result: "+xs.toString)
          xs match {
            case Success(v) => AuthCache.users.put(Creds(username, user.password))    // the password passed in to the cache should be the non-hashed one
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, v+" user added or updated successfully")
            case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
              ApiResponse(ApiResponseType.BAD_INPUT, "user not added or updated: "+t.toString)
          }
        })
      } else {      // update by existing user
        db.run(UserRow(username, user.password, user.email, ApiTime.nowUTC).updateUser).map({ xs =>     // updateUser() handles the case where pw or email is blank (i.e. do not update those fields)
          logger.debug("PUT /users/"+username+" result: "+xs.toString)
          try {
            val numUpdated = xs.toString.toInt
            if (numUpdated > 0) {
              if (user.password != "") AuthCache.users.put(Creds(username, user.password))    // the password passed in to the cache should be the non-hashed one
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "user updated successfully")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "user '"+username+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from user update: "+e) }    // the specific exception is NumberFormatException
        })
      }
    }
  })


  // =========== POST /users/{username} ===============================
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
        Parameter("username", DataType.String, Option[String]("Username of the user to be added."), paramType = ParamType.Path, required=false),
        Parameter("body", DataType[PutUsersRequest],
          Option[String]("User object that needs to be added to the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postUsers2 = (apiOperation[PutUsersRequest]("postUsers2") summary("a") notes("a"))  // for some bizarre reason, the PutUsersRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles POST /user/{username}. Called by anonymous (no creds) to add the user. */
  post("/users/:username", operation(postUsers)) ({
    // logger.info("POST /users/"+params("username"))
    // Note: we do not currently verify this is a real person creating this (with, for example, captcha), because haproxy restricts the number of
    //      times a single IP address can call this in a day to a very small number
    val username = params("username")
    validateUser(BaseAccess.CREATE, username)
    val user = try { parse(request.body).extract[PutUsersRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug(user.toString)
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      user.copyToTempDb(username, true)     // true means create
      status_=(HttpCode.POST_OK)
      ApiResponse(ApiResponseType.OK, "user added to the exchange")
    } else {
      val resp = response
      if (user.password == "" || user.email == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "both password and email must be non-blank when creating a user"))
      db.run(UserRow(username, user.password, user.email, ApiTime.nowUTC).insertUser.asTry).map({ xs =>
        logger.debug("POST /users/"+username+" result: "+xs.toString)
        xs match {
          case Success(v) => AuthCache.users.put(Creds(username, user.password))    // the password passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, v+" user added successfully")
          case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)     // this usually happens if the user already exists
            ApiResponse(ApiResponseType.BAD_INPUT, "user not added: "+t.toString)
        }
      })
    }
  })

  // =========== DELETE /users/{username} ===============================
  val deleteUsers =
    (apiOperation[ApiResponse]("deleteUsers")
      summary "Deletes a user"
      notes "Deletes a user from the exchange DB and all of its devices and agbots. Can only be run by that user or root."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of the user to be deleted."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles DELETE /users/{username}. */
  delete("/users/:username", operation(deleteUsers)) ({
    // logger.info("DELETE /users/"+params("username"))
    val username = params("username")
    validateUser(BaseAccess.WRITE, username)
    val resp = response
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      // remove does *not* throw an exception if the key does not exist
      TempDb.users.remove(username)
      AuthCache.users.remove(username)    // do this after removing from the real db, in case that fails
      resp.setStatus(HttpCode.DELETED)
      ApiResponse(ApiResponseType.OK, "user deleted from the exchange")
    } else {
      // now with all the foreign keys set up correctly and onDelete=cascade, the db will automatically delete the associated rows
      db.run(UsersTQ.getUser(username).delete).map({ xs =>
        logger.debug("DELETE /users/"+username+" result: "+xs.toString)
        try {
          val numDeleted = xs.toString.toInt
          if (numDeleted > 0) {
            AuthCache.users.remove(username)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "user deleted from the exchange")
          } else {
            resp.setStatus(HttpCode.DELETED)    // not considered an error, because they wanted the resource gone and it is
            ApiResponse(ApiResponseType.OK, "user '"+username+"' did not need to be deleted because it did not exist")
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from user delete: "+e) }    // the specific exception is NumberFormatException
      })
    }
  })

  // =========== POST /users/{username}/confirm ===============================
  val postUsersConfirm =
    (apiOperation[ApiResponse]("postUsersConfirm")
      summary "Confirms if this username/password is valid"
      notes "Confirms whether or not this username exists and has the specified password. Can only be run by that user or root."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of the user to be confirmed."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /users/{username}/confirm. */
  post("/users/:username/confirm", operation(postUsersConfirm)) ({
    // logger.info("POST /users/"+params("username")+"/confirm")
    // Note: the haproxy rate limiting guards against pw cracking attempts
    val username = params("username")
    validateUser(BaseAccess.READ, username)
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "confirmation successful")
  })

  // =========== POST /users/{username}/reset ===============================
  val postUsersReset =
    (apiOperation[ApiResponse]("postUsersReset")
      summary "Emails the user a token for resetting their password"
      notes """Use this if you have forgotten your password. (If you know your password and want to change it, you can use PUT /users/{username}.) Emails the user a timed token that can be given to POST /users/{username}/changepw. The token is good for 5 minutes. In the special case in which root's credentials are specified in the HTTP header, the token will not be emailed, instead it be returned in the response like this:

```
{
  "token": "<token>"
}
```"""
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of the user to be reset."), paramType = ParamType.Path)
        // Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /users/{username}/reset. */
  post("/users/:username/reset", operation(postUsersReset)) ({
    // logger.info("POST /users/"+params("username")+"/reset")
    val username = params("username")
    //TODO: anonymous needs to be allowed, but we need to prevent someone else flooding their email
    val creds = validateUser(BaseAccess.RESET_PW, username)

    if (isSuperUser(creds)) {
      // verify the username exists via the cache
      AuthCache.users.get(username) match {
        case Some(user) => ;      // do not need to do anything
        case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "username '"+username+"' not found"))
      }
      status_=(HttpCode.POST_OK)
      ResetPwResponse(createToken(username))
    }
    else {
      // need the user's email to send him the reset token
      if (ExchConfig.getBoolean("api.db.memoryDb")) {
        val user = TempDb.users.get(username) match {
          case Some(user) => user
          case None => halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "username "+username+"' not found"))
        }
        Email.send(username, user.email, createToken(username)) match {
          case Success(msg) => status_=(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, msg)
          case Failure(e) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, e.toString()))
        }
      } else {    // persistence
        val resp = response
        db.run(UsersTQ.getEmail(username).result).map({ xs =>
          logger.debug("POST /users/"+username+"/reset result: "+xs.toString)
          if (xs.size > 0) {
            val email = xs.head
            logger.debug("Emailing reset token for user "+username+" to email: "+email)
            Email.send(username, email, createToken(username)) match {
              case Success(msg) => resp.setStatus(HttpCode.POST_OK)
                ApiResponse(ApiResponseType.OK, msg)
              case Failure(e) => resp.setStatus(HttpCode.BAD_INPUT)
                ApiResponse(ApiResponseType.BAD_INPUT, e.toString())
            }
          } else {      // username not found in db
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "username "+username+"' not found")
          }
        })
      }
    }
  })

  // =========== POST /users/{username}/changepw ===============================
  val postUsersChangePw =
    (apiOperation[ApiResponse]("postUsersChangePw")
      summary "Changes the user's password using a reset token for authentication"
      notes "Use POST /users/{username}/reset to have a timed token sent to your email address. Then give that token and your new password to this REST API method."
      parameters(
        Parameter("username", DataType.String, Option[String]("Username of the user to be reset."), paramType = ParamType.Path),
        Parameter("password", DataType.String, Option[String]("Reset token obtained from POST /users/{username}/reset. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[ChangePwRequest],
          Option[String]("Your new password."),
          paramType = ParamType.Body)
        )
      )
  val postUsersChangePw2 = (apiOperation[ChangePwRequest]("postUsersChangePw2") summary("a") notes("a"))

  /** Handles POST /users/{username}/changepw. */
  post("/users/:username/changepw", operation(postUsersChangePw)) ({
    // logger.info("POST /users/"+params("username")+"/changepw")
    val username = params("username")
    validateToken(BaseAccess.WRITE, username)     // this validates the token that is passed in via the header (or via the password query parm)
    val req = try { parse(request.body).extract[ChangePwRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    if (ExchConfig.getBoolean("api.db.memoryDb")) {
      PutUsersRequest(req.newPassword, "").copyToTempDb(username, false)
      status_=(HttpCode.POST_OK)
      ApiResponse(ApiResponseType.OK, "password changed successfully")
    } else {
      val resp = response
      db.run(UserRow(username, req.newPassword, "", ApiTime.nowUTC).updateUser).map({ xs =>     // updateUser() handles the case where pw or email is blank (i.e. do not update those fields)
        logger.debug("POST /users/"+username+"/changepw result: "+xs.toString)
        try {
          val numUpdated = xs.toString.toInt
          if (numUpdated > 0) {
            AuthCache.users.put(Creds(username, req.newPassword))    // the password passed in to the cache should be the non-hashed one
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "password updated successfully")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "user '"+username+"' not found")
          }
        } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from user update: "+e) }    // the specific exception is NumberFormatException
      })
    }
  })

}
