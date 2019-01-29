/** Services routes for all of the /admin api methods. */
package com.horizon.exchangeapi

import java.util.Properties

import com.horizon.exchangeapi.auth.IbmCloudAuth
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.io.Source
//import scala.collection.immutable._
//import scala.collection.mutable.ListBuffer
import scala.util._

case class AdminHashpwRequest(password: String)
case class AdminHashpwResponse(hashedPassword: String)

case class AdminLogLevelRequest(loggingLevel: String)

case class AdminConfigRequest(varPath: String, value: String)

case class AdminDropdbTokenResponse(token: String)

case class GetAdminStatusResponse(msg: String, numberOfUsers: Int, numberOfNodes: Int, numberOfNodeAgreements: Int, numberOfNodeMsgs: Int, numberOfAgbots: Int, numberOfAgbotAgreements: Int, numberOfAgbotMsgs: Int, dbSchemaVersion: Int)
class AdminStatus() {
  var msg: String = ""
  var numberOfUsers: Int = 0
  var numberOfNodes: Int = 0
  var numberOfNodeAgreements: Int = 0
  var numberOfNodeMsgs: Int = 0
  var numberOfAgbots: Int = 0
  var numberOfAgbotAgreements: Int = 0
  var numberOfAgbotMsgs: Int = 0
  var dbSchemaVersion: Int = 0
  def toGetAdminStatusResponse = GetAdminStatusResponse(msg, numberOfUsers, numberOfNodes, numberOfNodeAgreements, numberOfNodeMsgs, numberOfAgbots, numberOfAgbotAgreements, numberOfAgbotMsgs, dbSchemaVersion)
}


/** Implementation for all of the /admin routes */
trait AdminRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  implicit def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  val dumpDir = "/tmp/exchange-tables"
  val dumpSuffix = ".json"

  // =========== POST /admin/reload ===============================
  val postAdminReload =
    (apiOperation[ApiResponse]("postAdminReload")
      summary "Tells the exchange reread its config file"
      description "Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  post("/admin/reload", operation(postAdminReload)) ({
    // validateUser(BaseAccess.ADMIN, "")
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    ExchConfig.reload()
    logger.debug("POST /admin/reload completed successfully.")
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "reload successful")
  })

  // =========== POST /admin/hashpw ===============================
  val postAdminHashPw =
    (apiOperation[AdminHashpwResponse]("postAdminHashPw")
      summary "Returns a salted hash of a password"
      description "Takes the password specified in the body, hashes it with a random salt, and returns the result. This can be useful if you to specify root's hash pw in the config file instead of the clear pw. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[AdminHashpwRequest],
          Option[String]("The clear text password."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )
  val postAdminHashPw2 = (apiOperation[AdminHashpwRequest]("postAdminHashPw2") summary("a") description("a"))

  post("/admin/hashpw", operation(postAdminHashPw)) ({
    // validateUser(BaseAccess.ADMIN, "")
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val req = try { parse(request.body).extract[AdminHashpwRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    status_=(HttpCode.POST_OK)
    AdminHashpwResponse(Password.hash(req.password))
  })

  // =========== PUT /admin/loglevel ===============================
  val putAdminLogLevel =
    (apiOperation[ApiResponse]("putAdminLogLevel")
      summary "Sets the logging level of the exchange"
      description "Dynamically set the logging level of the data exchange server, taking effect immediately. If POST /admin/reload is run at a later time, and logging.level is specified in the config.json file, that will overrided this setting. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[AdminLogLevelRequest],
          Option[String]("The new logging level: OFF, ERROR, WARN, INFO, DEBUG, TRACE, or ALL"),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"))
      )
  val putAdminLogLevel2 = (apiOperation[AdminLogLevelRequest]("putAdminLogLevel2") summary("a") description("a"))

  post("/admin/loglevel", operation(putAdminLogLevel)) ({
    // validateUser(BaseAccess.ADMIN, "")
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val req = try { parse(request.body).extract[AdminLogLevelRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    ExchConfig.levels.get(req.loggingLevel.toUpperCase) match {
      case Some(level) => ExchConfig.logger.setLevel(level)
      case None => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Invalid logging level '"+req.loggingLevel+"' specified."))
    }
    status_=(HttpCode.PUT_OK)
    ApiResponse(ApiResponseType.OK, "Logging level set")
  })

  // =========== GET /admin/dropdb/token ===============================
  val getDropdbToken =
    (apiOperation[AdminDropdbTokenResponse]("getDropdbToken")
      summary "Gets a 1-time token for dropping the DB"
      description "Returns a timed token that can be given to POST /admin/dropdb. The token is good for 10 minutes. Since dropping the DB tables deletes all of their data, this is a way of confirming you really want to do it. This can only be run as root."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  get("/admin/dropdb/token", operation(getDropdbToken)) ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    response.setStatus(HttpCode.OK)
    AdminDropdbTokenResponse(createToken(Role.superUser))
  })

  // =========== POST /admin/dropdb ===============================
  val postAdminDropDb =
    (apiOperation[ApiResponse]("postAdminDropDb")
      summary "Deletes the tables from the DB"
      description "Deletes the tables from the Exchange DB. **Warning: this will delete the data too!** Because this is a dangerous method, you must first get a 1-time token using GET /admin/dropdb/token, and use that to authenticate to this REST API method. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The token received from GET /admin/dropdb/token. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  post("/admin/dropdb", operation(postAdminDropDb)) ({
    // validateToken(BaseAccess.ADMIN, "")     // the token was generated for root, so will only work for root
    authenticate(hint = "token").authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    // ApiResponse(ApiResponseType.OK, "would delete db")
    db.run(ExchangeApiTables.dropDB.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/dropdb result: "+xs.toString)
      xs match {
        case Success(_) => AuthCache.nodes.removeAll()     // i think we could just let the cache catch up over time, but seems better to clear it out now
          AuthCache.users.removeAll()
          AuthCache.agbots.removeAll()
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db deleted successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not deleted: "+t.toString)
      }
    })
  })

  // =========== POST /admin/initdb ===============================
  val postAdminInitDb =
    (apiOperation[ApiResponse]("postAdminInitDb")
      summary "Creates the table schema in the DB"
      description "Creates the tables with the necessary schema in the Exchange DB. This is now called at exchange startup, if necessary. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  post("/admin/initdb", operation(postAdminInitDb)) ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    db.run(ExchangeApiTables.initDB.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/initdb init table schemas result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ExchConfig.createRoot(db)         // initialize the users table with the root user from config.json
          ApiResponse(ApiResponseType.OK, "db initialized successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not initialized: "+t.toString)
      }
    })
  })

  // =========== POST /admin/upgradedb ===============================
  val postAdminUpgradeDb =
    (apiOperation[ApiResponse]("postAdminUpgradeDb")
      summary "Upgrades the DB schema"
      description "Updates (alters) the schemas of the DB tables as necessary (w/o losing any data) to get to the latest DB schema. This is now called at exchange startup, if necessary. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  post("/admin/upgradedb", operation(postAdminUpgradeDb)) ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    val upgradeNotNeededMsg = "DB schema does not need upgrading, it is already at the latest schema version: "

    // Assemble the list of db actions to alter schema of existing tables and create tables that are new in each of the schema versions we have to catch up on
    db.run(SchemaTQ.getSchemaRow.result.asTry.flatMap({ xs =>
      logger.debug("POST /admin/upgradedb current schema result: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {
            val schemaRow = v.head
            if (SchemaTQ.isLatestSchemaVersion(schemaRow.schemaVersion)) DBIO.failed(new Throwable(upgradeNotNeededMsg + schemaRow.schemaVersion)).asTry    // I do not think there is a way to pass a msg thru the Success path
            else SchemaTQ.getUpgradeActionsFrom(schemaRow.schemaVersion).transactionally.asTry
          }
          else DBIO.failed(new Throwable("DB upgrade error: did not find a row in the schemas table")).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /admin/upgradedb result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "DB table schema upgraded to the latest successfully")
        case Failure(t) => if (t.getMessage.contains(upgradeNotNeededMsg)) {
            resp.setStatus(HttpCode.POST_OK)
            ApiResponse(ApiResponseType.OK, t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "DB table schema not upgraded: " + t.toString)
          }
      }
    })
  })

  /* Someday we should support this....
  // =========== POST /admin/migratedb ===============================
  val postAdminMigrateDb =
    (apiOperation[ApiResponse]("postAdminMigrateDb")
      summary "Migrates the DB to a new schema"
      description "Consider running POST /admin/upgradedb instead. Note: for now you must run POST /admin/dumptables before running this. Dumps all of the tables to files, drops the tables, creates the tables (usually with new schema), and loads the tables from the files. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("skipload", DataType.String, Option[String]("Set to 'yes' if you want to load the tables later (via POST /admin/loadtables) after you have edited the json files to conform to the new schema."), paramType=ParamType.Query, required=false)
        )
      )

  post("/admin/migratedb", operation(postAdminMigrateDb)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val skipLoad: Boolean = if (params.get("skipLoad").orNull != null && params("skipload").toLowerCase == "yes") true else false
    // val skipLoad: Boolean = true    //Note: ExchangeApiTables.load() tries to read the json file content immediately, instead of waiting until they have been dumped
    migratingDb = true      // lock non-root people out of rest api calls
    val resp = response

    // Assemble the list of db actions to: dump tables, drop db, init db, (optionally) load tables
    val dbActions = ListBuffer[DBIO[_]]()
    dbActions += ExchangeApiTables.dump(dumpDir, dumpSuffix)
    dbActions += ExchangeApiTables.deletePrevious
    dbActions += ExchangeApiTables.create
    if (!skipLoad) dbActions ++= ExchangeApiTables.load(dumpDir, dumpSuffix)
    val dbio = DBIO.seq(dbActions: _*)      // convert the list of actions to a DBIO seq

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(dbio.asTry).map({ xs =>
      logger.debug("POST /admin/migratedb result: "+xs.toString)
      xs match {
        case Success(_) => migratingDb = false    // let clients run rest api calls again
          resp.setStatus(HttpCode.POST_OK)
          // AuthCache.users.init(db)     // instead of doing this we can let the cache build up over time as resources are accessed
          // AuthCache.nodes.init(db)
          // AuthCache.agbots.init(db)
          // AuthCache.bctypes.init(db)
          // AuthCache.blockchains.init(db)
          ApiResponse(ApiResponseType.OK, "db tables migrated successfully")
          // ApiResponse(ApiResponseType.OK, "db tables dumped and schemas migrated, now load tables using POST /admin/loadtables")    //TODO:
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db tables not migrated: "+t.toString)
      }
    })
  })
  */

  /* Just for re-testing upgrade...
  // =========== POST /admin/downgradedb ===============================
  val postAdminDowngradeDb =
    (apiOperation[ApiResponse]("postAdminDowngradeDb")
      summary "Undoes the upgrades of the DB schema"
      description "Undoes the updates (alters) of the schemas of the db tables in case we need to fix the upgradedb code and try it again. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
  */

  post("/admin/downgradedb" /*, operation(postAdminDowngradeDb)*/) ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response

    // Get the list of db actions to: delete tables that are new in this version, and unalter schema changes made to existing tables
    // val dbActions = DBIO.seq(ExchangeApiTables.deleteNewTables, ExchangeApiTables.unAlterTables)
    val dbActions = ExchangeApiTables.deleteNewTables

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(SchemaTQ.getSchemaRow.result.asTry.flatMap({ xs =>
      logger.debug("POST /admin/upgradedb current schema result: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {
          val schemaRow = v.head
          // Probably should do the dbActions 1st, but this is more convenient because we have the schemaVersion right now
          SchemaTQ.getDecrementVersionAction(schemaRow.schemaVersion).asTry
        }
        else DBIO.failed(new Throwable("DB downgrade error: did not find a row in the schemas table")).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    }).flatMap({ xs =>
      logger.debug("POST get schema row result: "+xs.toString)
      xs match {
        case Success(_) => dbActions.asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /admin/downgrade result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db table schemas downgraded successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db table schemas not downgraded: "+t.toString)
      }
    })
  })

  /* Someday we should clean this up and support this...
  // =========== POST /admin/dumptables ===============================
  val postAdminDumpTables =
    (apiOperation[Seq[String]]("postAdminDumpTables")
      summary "Dumps all the DB tables"
      description "Dumps all the DB tables to files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/dumptables" /*, operation(postAdminDumpTables)*/ ) ({
    // validateUser(BaseAccess.ADMIN, "")
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    val dbAction = ExchangeApiTables.dump(dumpDir, dumpSuffix)    // this action queries all the tables and writes them to files
    db.run(dbAction.asTry).map({ xs =>
      logger.debug("POST /admin/dumptables result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "tables dumped to "+dumpDir+" successfully")
        case Failure(t) => logger.error("error in dumping tables: "+t.toString)
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "error in dumping tables: "+t.toString)
      }
    })
  })

  /* Someday we should clean this up and support this...
  // =========== POST /admin/loadtables ===============================
  val postAdminLoadTables =
    (apiOperation[Seq[String]]("postAdminLoadTables")
      summary "Loads content for all the DB tables"
      description "Loads content for all the DB tables from files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/loadtables" /*, operation(postAdminLoadTables) */) ({
    // validateUser(BaseAccess.ADMIN, "")
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    val dbActions = try { ExchangeApiTables.load(dumpDir, dumpSuffix) }   // read/parse all the json files and create actions to put the contents in the tables
    catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Error parsing json table file: "+e)) }  // to catch the json parsing exception from TableIo.load()

    val dbio = DBIO.seq(dbActions: _*)      // convert the list of actions to a DBIO seq
    db.run(dbio.asTry).map({ xs =>      // currently not doing it transactionally because it is easier to find the error that way, and they can always drop the db and try again
      logger.debug("POST /admin/loadtables result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)    // let the auth cache build up gradually
          ApiResponse(ApiResponseType.OK, "tables restored successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "tables not fully restored: "+t.toString)
      }
    })
  })

  // =========== GET /admin/version ===============================
  val getAdminVersion =
    (apiOperation[String]("getAdminVersion")
      summary "Returns the version of the Exchange server"
      description "Returns the version of the Exchange server as a simple string (no JSON or quotes). Can be run by anyone."
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"))
      )

  get("/admin/version", operation(getAdminVersion)) ({
    credsAndLogForAnonymous()     // do not need to call authenticate().authorizeTo() because anyone can run this
    val versionSource = Source.fromResource("version.txt")      // returns BufferedSource
    val versionText : String = versionSource.getLines.next()
    versionSource.close()
    response.setStatus(HttpCode.OK)
    versionText + "\n"
  })


  // =========== GET /admin/status ===============================
  val getAdminStatus =
    (apiOperation[GetAdminStatusResponse]("getAdminStatus")
      summary "Returns status of the Exchange server"
      description "Returns a dictionary of statuses/statistics. Can be run by any user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The password. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
      )

  get("/admin/status", operation(getAdminStatus)) ({
    authenticate().authorizeTo(TAction(),Access.STATUS)
    val resp = response
    val statusResp = new AdminStatus()
    //TODO: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
    db.run(UsersTQ.rows.length.result.asTry.flatMap({ xs =>
      logger.debug("GET /admin/status users length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfUsers = v
          NodesTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status nodes length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfNodes = v
          AgbotsTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status agbots length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfAgbots = v
          NodeAgreementsTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status devagreements length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfNodeAgreements = v
          AgbotAgreementsTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status agbotagreements length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfAgbotAgreements = v
          NodeMsgsTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status devmsgs length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfNodeMsgs = v
          AgbotMsgsTQ.rows.length.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("GET /admin/status agbotmsgs length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfAgbotMsgs = v
          SchemaTQ.getSchemaVersion.result.asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("GET /admin/status schemaversion: "+xs)
      xs match {
        case Success(v) => statusResp.dbSchemaVersion = v.head
          statusResp.msg = "Exchange server operating normally"
          resp.setStatus(HttpCode.OK)
        case Failure(t) => statusResp.msg = t.getMessage
          resp.setStatus(HttpCode.INTERNAL_ERROR)
      }
      statusResp.toGetAdminStatusResponse
    })
  })

  /** set 1 or more variables in the in-memory config (so it does not do the right thing in multi-node mode).
   * Intentionally not put swagger, because only used by automated tests. */
  put("/admin/config") ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    val mod = try { parse(request.body).extract[AdminConfigRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.debug("PUT /admin/config mod: "+mod)
    val props = new Properties()
    props.setProperty(mod.varPath, mod.value)
    ExchConfig.mod(props)
    // logger.debug("config value: "+ExchConfig.getInt(mod.varPath))
    resp.setStatus(HttpCode.PUT_OK)    // let the auth cache build up gradually
    ApiResponse(ApiResponseType.OK, "Config value set successfully")
  })

  /** Dev testing of db access */
  post("/admin/test") ({
    authenticate().authorizeTo(TAction(),Access.ADMIN)
    //val resp = response

    ApiResponse(ApiResponseType.OK, "done")

    /*
    // ApiResponse(ApiResponseType.OK, "Now: "+ApiTime.nowUTC+", Then: "+ApiTime.pastUTC(100)+".")
    val ttl = 2 * 86400
    val oldestTime = ApiTime.pastUTC(ttl)
    val q = NodeMsgsTQ.rows.filter(_.timeSent < oldestTime)
    db.run(q.result).map({ list =>
      logger.debug("GET /admin/gettest result size: "+list.size)
      logger.trace("GET /admin/gettest result: "+list.toString)
      val listSorted = list.sortWith(_.msgId < _.msgId)
      val msgs = new ListBuffer[NodeMsg]
      if (listSorted.size > 0) for (m <- listSorted) { msgs += m.toNodeMsg }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodeMsgsResponse(msgs.toList, 0)
    })
    */
  })

  // =========== POST /admin/clearAuthCaches ===============================
  val postAdminClearAuthCaches =
    (apiOperation[ApiResponse]("postAdminClearAuthCaches")
      summary "Tells the exchange clear its authentication cache"
      description "Directs the exchange server to clear its authentication cache. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"))
    )

  post("/admin/clearAuthCaches") ({
    authenticate().authorizeTo(TAction(), Access.ADMIN)
    //todo: ensure other client requests are not updating the cache at the same time
    IbmCloudAuth.clearCache()
    AuthCache.agbots.removeAll()
    AuthCache.nodes.removeAll()
    AuthCache.patterns.removeAll()
    AuthCache.resources.removeAll()
    AuthCache.services.removeAll()
    AuthCache.users.removeAll()
    ApiResponse(ApiResponseType.OK, "cache cleared")
  })

}