/** Services routes for all of the /admin api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
// import slick.driver.PostgresDriver.api._
// import slick.basic.DatabasePublisher
// import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import org.slf4j._
import com.horizon.exchangeapi.tables._
import Access._
import BaseAccess._
import java.io._
import scala.util._
import scala.util.control.Breaks._
import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}   //renaming this so i do not have to qualify every use of a immutable collection
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._

case class AdminHashpwRequest(password: String)
case class AdminHashpwResponse(hashedPassword: String)

case class AdminLogLevelRequest(loggingLevel: String)

case class AdminDropdbTokenResponse(token: String)

// type AdminPutTableDummyRequest = Seq[String]


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
      notes "Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/reload. */
  post("/admin/reload", operation(postAdminReload)) ({
    // logger.info("POST /admin/reload")
    validateUser(BaseAccess.ADMIN, "")
    ExchConfig.reload
    logger.debug("POST /admin/reload completed successfully.")
    status_=(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "reload successful")
  })

  // =========== POST /admin/hashpw ===============================
  val postAdminHashPw =
    (apiOperation[AdminHashpwResponse]("postAdminHashPw")
      summary "Returns a salted hash of a password"
      notes "Takes the password specified in the body, hashes it with a random salt, and returns the result. This can be useful if you to specify root's hash pw in the config file instead of the clear pw. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[AdminHashpwRequest],
          Option[String]("The clear text password."),
          paramType = ParamType.Body)
        )
      )
  val postAdminHashPw2 = (apiOperation[AdminHashpwRequest]("postAdminHashPw2") summary("a") notes("a"))

  /** Handles POST /admin/hashpw. */
  post("/admin/hashpw", operation(postAdminHashPw)) ({
    // logger.info("POST /admin/hashpw")
    validateUser(BaseAccess.ADMIN, "")
    val req = try { parse(request.body).extract[AdminHashpwRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    status_=(HttpCode.POST_OK)
    AdminHashpwResponse(Password.hash(req.password))
  })

  // =========== PUT /admin/loglevel ===============================
  val putAdminLogLevel =
    (apiOperation[ApiResponse]("putAdminLogLevel")
      summary "Sets the logging level of the exchange"
      notes "Dynamically set the logging level of the data exchange server, taking effect immediately. If POST /admin/reload is run at a later time, and logging.level is specified in the config.json file, that will overrided this setting. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[AdminLogLevelRequest],
          Option[String]("The new logging level: OFF, ERROR, WARN, INFO, DEBUG, TRACE, or ALL"),
          paramType = ParamType.Body)
        )
      )
  val putAdminLogLevel2 = (apiOperation[AdminLogLevelRequest]("putAdminLogLevel2") summary("a") notes("a"))

  /** Handles POST /admin/loglevel. */
  post("/admin/loglevel", operation(putAdminLogLevel)) ({
    // logger.info("POST /admin/loglevel")
    validateUser(BaseAccess.ADMIN, "")
    val req = try { parse(request.body).extract[AdminLogLevelRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    ExchConfig.levels.get(req.loggingLevel.toUpperCase) match {
      case Some(level) => ExchConfig.logger.setLevel(level)
      case None => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Invalid logging level '"+req.loggingLevel+"' specified."))
    }
    status_=(HttpCode.PUT_OK)
    ApiResponse(ApiResponseType.OK, "Logging level set")
  })

  // =========== POST /admin/initdb ===============================
  val postAdminInitDb =
    (apiOperation[ApiResponse]("postAdminInitDb")
      summary "Creates the table schema in the DB"
      notes "Creates the tables with the necessary schema in the Exchange DB. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/initdb. */
  post("/admin/initdb", operation(postAdminInitDb)) ({
    // logger.info("POST /admin/initdb")
    validateRoot(BaseAccess.ADMIN)
    val resp = response
    // db.run(ExchangeApiTables.setup).flatMap[ApiResponse]({ x =>
    db.run(ExchangeApiTables.create.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/initdb result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          // AuthCache.users.init(db)     // instead of doing this we can let the cache build up over time as resource are accessed
          // AuthCache.devices.init(db)
          // AuthCache.agbots.init(db)
          ApiResponse(ApiResponseType.OK, "db initialized successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not initialized: "+t.toString)
      }
    })
  })

  // =========== POST /admin/initnewtables ===============================
  val postAdminInitNewTables =
    (apiOperation[ApiResponse]("postAdminInitNewTables")
      summary "Creates the schema for the new tables in this version"
      notes "Creates the tables, that are new in this exchange version, with the necessary schema in the Exchange DB. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/initnewtables. */
  post("/admin/initnewtables", operation(postAdminInitNewTables)) ({
    validateRoot(BaseAccess.ADMIN)
    val resp = response
    db.run(ExchangeApiTables.createNewTables.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/initnewtables result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "new tables initialized successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "new tables not initialized: "+t.toString)
      }
    })
  })

  // =========== GET /admin/dropdb/token ===============================
  val getDropdbToken =
    (apiOperation[AdminDropdbTokenResponse]("getDropdbToken")
      summary "Gets a 1-time token for dropping the DB"
      notes "Returns a timed token that can be given to POST /admin/dropdb. The token is good for 5 minutes. Since dropping the DB tables deletes all of their data, this is a way of confirming you really want to do it. This can only be run as root."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /admin/dropdb/token. */
  get("/admin/dropdb/token", operation(getDropdbToken)) ({
    // logger.info("GET /admin/dropdb/token")
    validateRoot(BaseAccess.ADMIN)
    status_=(HttpCode.POST_OK)
    AdminDropdbTokenResponse(createToken("root"))
  })

  // =========== POST /admin/dropdb ===============================
  val postAdminDropDb =
    (apiOperation[ApiResponse]("postAdminDropDb")
      summary "Deletes the tables from the DB"
      notes "Deletes the tables from the Exchange DB. **Warning: this will delete the data too!** Because this is a dangerous method, you must first get a 1-time token using GET /admin/dropdb/token, and use that to authenticate to this REST API method. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The token received from GET /admin/dropdb/token. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/dropdb. */
  post("/admin/dropdb", operation(postAdminDropDb)) ({
    // logger.info("POST /admin/dropdb")
    validateToken(BaseAccess.ADMIN, "")     // the token was generated for root, so will only work for root
    val resp = response
    // ApiResponse(ApiResponseType.OK, "would delete db")
    db.run(ExchangeApiTables.delete.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/dropdb result: "+xs.toString)
      xs match {
        case Success(v) => AuthCache.devices.removeAll     // i think we could just let the cache catch up over time, but seems better to clear it out now
          AuthCache.users.removeAll
          AuthCache.agbots.removeAll
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db deleted successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not completely deleted: "+t.toString)
      }
    })
  })

  // =========== POST /admin/dropnewtables ===============================
  val postAdminDropNewTables =
    (apiOperation[ApiResponse]("postAdminDropNewTables")
      summary "Deletes the tables that are new in this version"
      notes "Deletes the tables from the Exchange DB that are new in this version. **Warning: this will delete the data too!** Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The token received from GET /admin/dropdb/token. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/dropnewtables. */
  post("/admin/dropnewtables", operation(postAdminDropNewTables)) ({
    validateRoot(BaseAccess.ADMIN)
    val resp = response
    db.run(ExchangeApiTables.deleteNewTables.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/dropnewtables result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "new tables deleted successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "new tables not completely deleted: "+t.toString)
      }
    })
  })

  // =========== POST /admin/migratedb ===============================
  val postAdminMigrateDb =
    (apiOperation[ApiResponse]("postAdminMigrateDb")
      summary "Migrates the DB to a new schema"
      notes "Note: for now you must run POST /admin/dumptables before running this. Dumps all of the tables to files, drops the tables, creates the tables (usually with new schema), and loads the tables from the files. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("skipload", DataType.String, Option[String]("Set to 'yes' if you want to load the tables later (via POST /admin/loadtables) after you have edited the json files to conform to the new schema."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/migratedb. */
  post("/admin/migratedb", operation(postAdminMigrateDb)) ({
    validateRoot(BaseAccess.ADMIN)
    val skipLoad: Boolean = if (params.get("skipLoad").orNull != null && params("skipload").toLowerCase == "yes") true else false
    // val skipLoad: Boolean = true    //TODO: ExchangeApiTables.load() tries to read the json file content immediately, instead of waiting until they have been dumped
    migratingDb = true      // lock non-root people out of rest api calls
    val resp = response

    // Assemble the list of db actions to: dump tables, drop db, init db, (optionally) load tables
    val dbActions = ListBuffer[DBIO[_]]()
    dbActions += ExchangeApiTables.dump(dumpDir, dumpSuffix)
    dbActions += ExchangeApiTables.delete
    dbActions += ExchangeApiTables.create
    if (!skipLoad) dbActions ++= ExchangeApiTables.load(dumpDir, dumpSuffix)
    val dbio = DBIO.seq(dbActions: _*)      // convert the list of actions to a DBIO seq

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(dbio.asTry).map({ xs =>
      logger.debug("POST /admin/migratedb result: "+xs.toString)
      xs match {
        case Success(v) => migratingDb = false    // let clients run rest api calls again
          resp.setStatus(HttpCode.POST_OK)
          // AuthCache.users.init(db)     // instead of doing this we can let the cache build up over time as resource are accessed
          ApiResponse(ApiResponseType.OK, "db tables migrated successfully")
          // ApiResponse(ApiResponseType.OK, "db tables dumped and schemas migrated, now load tables using POST /admin/loadtables")    //TODO:
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db tables not migrated: "+t.toString)
      }
    })
  })

  // =========== POST /admin/upgradedb ===============================
  val postAdminUpgradeDb =
    (apiOperation[ApiResponse]("postAdminUpgradeDb")
      summary "Upgrades the DB schema"
      notes "Updates (alters) the schemas of the db tables as necessary (w/o losing any data). Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/upgradedb. */
  post("/admin/upgradedb", operation(postAdminUpgradeDb)) ({
    validateRoot(BaseAccess.ADMIN)
    val resp = response

    // Assemble the list of db actions to: alter schema of existing tables, and create tables that are new in this version
    val dbActions = DBIO.seq(ExchangeApiTables.alterTables, ExchangeApiTables.createNewTables)

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(dbActions.asTry).map({ xs =>
      logger.debug("POST /admin/upgradedb result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db table schemas upgraded successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db table schemas not upgraded: "+t.toString)
      }
    })
  })

  // =========== POST /admin/unupgradedb ===============================
  val postAdminUnupgradeDb =
    (apiOperation[ApiResponse]("postAdminUnupgradeDb")
      summary "Undoes the upgrades of the DB schema"
      notes "Undoes the updates (alters) of the schemas of the db tables in case we need to fix the upgradedb code and try it again. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/unupgradedb. */
  post("/admin/unupgradedb", operation(postAdminUnupgradeDb)) ({
    validateRoot(BaseAccess.ADMIN)
    val resp = response

    // Assemble the list of db actions to: delete tables that are new in this version, and unalter schema changes made to existing tables
    val dbActions = DBIO.seq(ExchangeApiTables.deleteNewTables, ExchangeApiTables.unAlterTables)

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(dbActions.asTry).map({ xs =>
      logger.debug("POST /admin/unupgradedb result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db table schemas unupgraded successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db table schemas not unupgraded: "+t.toString)
      }
    })
  })

  // =========== POST /admin/dumptables ===============================
  val postAdminDumpTables =
    (apiOperation[Seq[String]]("postAdminDumpTables")
      summary "Dumps all the DB tables"
      notes "Dumps all the DB tables to files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/dumptables. */
  post("/admin/dumptables", operation(postAdminDumpTables)) ({
    validateUser(BaseAccess.ADMIN, "")
    val resp = response
    val dbAction = ExchangeApiTables.dump(dumpDir, dumpSuffix)    // this action queries all the tables and writes them to files
    db.run(dbAction.asTry).map({ xs =>
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "tables dumped to "+dumpDir+" successfully")
        case Failure(t) => logger.error("error in dumping tables: "+t.toString)
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "error in dumping tables: "+t.toString)
      }
    })

    /*
    resp.setStatus(HttpCode.POST_OK)      //TODO: only doing this because the stuff below does not get back to the client
    val aggFut = ExchangeApiTables.dump(db, resp, dumpDir, dumpSuffix)
    aggFut onComplete {
      case Success(v) => logger.info("aggFut success v: "+v)
        //TODO: figure out why this does not get back to the rest client
        resp.setStatus(HttpCode.POST_OK)
        ApiResponse(ApiResponseType.OK, "tables dumped to "+dumpDir+" successfully")
      case Failure(t) => logger.error("error in dumping tables: "+t.toString)
        resp.setStatus(HttpCode.INTERNAL_ERROR)
        ApiResponse(ApiResponseType.INTERNAL_ERROR, "error in dumping tables: "+t.toString)
    }

    db.run(UsersTQ.rows.result).map({ xs =>
      val filename = dir+"/users"+suffix;  logger.debug("POST /admin/dumptables "+filename+" result size: "+xs.size)
      new TableIo[UserRow](filename).dump(xs)
      resp.setStatus(HttpCode.POST_OK);  ApiResponse(ApiResponseType.OK, "db tables dumped to "+dir+" successfully")
    })
    db.run(DevicesTQ.rows.result).map({ xs =>
      val filename = dir+"/devices"+suffix;  logger.debug("POST /admin/dumptables "+filename+" result size: "+xs.size)
      new TableIo[DeviceRow](filename).dump(xs)
      resp.setStatus(HttpCode.POST_OK);  ApiResponse(ApiResponseType.OK, "db tables dumped to "+dir+" successfully")
    })
    */
  })

  // =========== POST /admin/loadtables ===============================
  val postAdminLoadTables =
    (apiOperation[Seq[String]]("postAdminLoadTables")
      summary "Loads content for all the DB tables"
      notes "Loads content for all the DB tables from files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles POST /admin/loadtables. */
  post("/admin/loadtables", operation(postAdminLoadTables)) ({
    validateUser(BaseAccess.ADMIN, "")
    val resp = response
    val dbActions = try { ExchangeApiTables.load(dumpDir, dumpSuffix) }   // read/parse all the json files and create actions to put the contents in the tables
    catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Error parsing json table file: "+e)) }  // to catch the json parsing exception from TableIo.load()

    val dbio = DBIO.seq(dbActions: _*)      // convert the list of actions to a DBIO seq
    db.run(dbio.asTry).map({ xs =>      // currently not doing it transactionally because it is easier to find the error that way, and they can always drop the db and try again
      logger.debug("POST /admin/loadtables result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)    // let the auth cache build up gradually
          ApiResponse(ApiResponseType.OK, "tables restored successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "tables not fully restored: "+t.toString)
      }
    })

  })

  // =========== GET /admin/tables/{table} ===============================
  val getAdminTable =
    (apiOperation[Seq[String]]("getAdminTable")
      summary "Dumps a table's rows"
      notes "Dumps a table out in a format that can be given to PUT /admin/tables/{table}. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  /** Handles GET /admin/tables/{table}. */
  get("/admin/tables/:table", operation(getAdminTable)) ({
    // logger.info("GET /admin/tables/"+params("table"))
    validateUser(BaseAccess.ADMIN, "")
    val table = params("table")
    val resp = response
    val q = table match {
      case "users" => UsersTQ.rows
      case _ => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Unrecognized table name: "+table+" (use the table name in the postgres DB)"))
    }
    db.run(q.result).map({ xs =>
      logger.debug("GET /admin/tables/"+table+" result size: "+xs.size)
      resp.setStatus(HttpCode.OK)
      xs
    })
  })

  // =========== PUT /admin/tables/{table} ===============================
  val putAdminTable =
    (apiOperation[ApiResponse]("putAdminTable")
      summary "Restores a table's rows"
      notes """Restores a table's content from output from GET /admin/tables/{table}. Can only be run by the root user.
```
[
  {<row content in json format>},
]
```"""
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[Seq[String]],
          Option[String]("List of rows of the tables. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )

  /** Handles PUT /admin/tables/{table}. */
  put("/admin/tables/:table", operation(putAdminTable)) ({
    // logger.info("PUT /admin/tables/"+params("table"))
    validateRoot(BaseAccess.ADMIN)
    val table = params("table")
    val resp = response
    table match {
      case "users" => val users = try { parse(request.body).extract[Seq[UserRow]] }
        catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
        val actions = ListBuffer[DBIO[_]]()
        for (u <- users) {
          actions += (UsersTQ.rows += u)
        }
        val dbio = DBIO.seq(actions.toList: _*)      // convert the list of actions to a DBIO seq
        db.run(dbio.transactionally.asTry).map({ xs =>
          logger.debug("PUT /admin/tables/"+table+" result: "+xs.toString)
          xs match {
            case Success(v) => resp.setStatus(HttpCode.PUT_OK)    // let the auth cache build up gradually
              ApiResponse(ApiResponseType.OK, table+" table restored successfully")
            case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
              ApiResponse(ApiResponseType.INTERNAL_ERROR, "table '"+table+"' not restored: "+t.toString)
          }
        })
      case _ => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Unrecognized table name: "+table+" (use the table name in the postgres DB)"))
    }
  })

  /** Dev testing of db access */
  get("/admin/gettest") ({
    // logger.info("GET /admin/gettest")
    validateUser(BaseAccess.ADMIN, "")
    val resp = response

    // val prop = Prop("arch", "arm", "string", "in")
    val propList = List(Prop("arch", "arm", "string", "in"),Prop("memory","300","int",">="),Prop("version","1.0.0","version","in"),Prop("dataVerification","true","boolean","="))
    // val sw = Map[String,String]("a" -> "b", "c" -> "d")
    val sw = Map[String,String]()
    // val str = compact(render(prop))
    val str = write(sw)
    println("str: "+str)

    // val device = try { parse(str).extract[Prop] }
    // catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    // val props = read[List[Prop]](str)
    // println("props: "+props)

    val swVersions = read[Map[String,String]](str)
    println("swVersions: "+swVersions)
    resp.setStatus(HttpCode.POST_OK)
    ApiResponse(ApiResponseType.OK, "successful")

    /*
    // db.run(UsersTQ.getUser("bp").result).flatMap[ApiResponse]({ x =>
    db.run(UsersTQ.getUser("bp").result).map({ x =>
      println(x)
      if (x.size > 0) {
        resp.setStatus(HttpCode.POST_OK)
        // Future(ApiResponse(ApiResponseType.OK, "get was successful for "+x.head._1))
        ApiResponse(ApiResponseType.OK, "get was successful for "+x.head.username)
      } else {
        resp.setStatus(HttpCode.NOT_FOUND)
        ApiResponse(ApiResponseType.OK, "get was unsuccessful")

      }
    })

    val a = UsersTQ.rows.filter(_.username === "bp").map(_.password).result
    val pwVector = Await.result(db.run(a), Duration(1000, MILLISECONDS))
    val hashedPw: String = if (pwVector.size > 0) pwVector.head else "<not there>"

    ApiResponse(ApiResponseType.OK, "hashedPw: "+hashedPw)

    // val p: DatabasePublisher[String] = db.stream(a)
    // p.foreach { t => hashedPw = t; logger.info("t: "+t) }

    val q = for {
      m <- MicroservicesTQ.rows if m.deviceId === "1"
      d <- m.device
    } yield (d.id, d.token, d.name, d.owner, d.msgEndPoint, d.lastHeartbeat, m.url, m.numAgreements, m.policy)

    val q = for {
      m <- TestMicrosTQ.rows if m.deviceId === "d1"
      d <- m.device
    } yield (d.id, d.name, d.owner, m.name, m.url, m.numAgreements)

    val q2 = for {
      (m, d) <- ExchangeApiTables.testmicros zip ExchangeApiTables.testdevices
    } yield (d.id, d.name, d.owner, m.name, m.url)

    val q = for {
      m <- MicroservicesTQ.rows if m.deviceId === "1"
      d <- m.device
      p <- PropsTQ.rows if p.msId === m.msId
    } yield (d.id, d.token, d.name, d.owner, d.msgEndPoint, d.lastHeartbeat, m.url, m.numAgreements, m.policy, p.name, p.value, p.propType, p.op)

    db.run(q.result)

    db.run(q.result).map({ list =>
      case class Join(id: String, token: String, name: String, owner: String, msgEndPoint: String, lastHeartbeat: String, url: String, numAgreements: Int, policy: String, pname: String, value: String, propType: String, op: String)
      var joins = ListBuffer[Join]()
      for (e <- list) {
        joins += Join(e._1, e._2, e._3, e._4, e._5, e._6, e._7, e._8, e._9, e._10, e._11, e._12, e._13)
      }
      joins.toList
    })
    */
  })

}