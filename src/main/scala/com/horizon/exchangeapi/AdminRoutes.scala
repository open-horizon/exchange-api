/** Services routes for all of the /admin api methods. */
package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra.swagger._
import org.slf4j._
import java.util.Properties
//import scala.collection.immutable._
//import scala.collection.mutable.ListBuffer
import scala.util._

case class AdminHashpwRequest(password: String)
case class AdminHashpwResponse(hashedPassword: String)

case class AdminLogLevelRequest(loggingLevel: String)

case class AdminConfigRequest(varPath: String, value: String)

case class AdminDropdbTokenResponse(token: String)

case class GetAdminStatusResponse(msg: String, numberOfUsers: Int, numberOfNodes: Int, numberOfNodeAgreements: Int, numberOfNodeMsgs: Int, numberOfAgbots: Int, numberOfAgbotAgreements: Int, numberOfAgbotMsgs: Int)
class AdminStatus() {
  var msg: String = ""
  var numberOfUsers: Int = 0
  var numberOfNodes: Int = 0
  var numberOfNodeAgreements: Int = 0
  var numberOfNodeMsgs: Int = 0
  var numberOfAgbots: Int = 0
  var numberOfAgbotAgreements: Int = 0
  var numberOfAgbotMsgs: Int = 0
  def toGetAdminStatusResponse = GetAdminStatusResponse(msg, numberOfUsers, numberOfNodes, numberOfNodeAgreements, numberOfNodeMsgs, numberOfAgbots, numberOfAgbotAgreements, numberOfAgbotMsgs)
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
      notes "Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  post("/admin/reload", operation(postAdminReload)) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    ExchConfig.reload()
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

  post("/admin/hashpw", operation(postAdminHashPw)) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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

  post("/admin/loglevel", operation(putAdminLogLevel)) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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

  post("/admin/initdb", operation(postAdminInitDb)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    // db.run(ExchangeApiTables.setup).flatMap[ApiResponse]({ x =>
    db.run(ExchangeApiTables.create.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/initdb result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ExchConfig.createRoot(db)         // initialize the users table with the root user from config.json
          ApiResponse(ApiResponseType.OK, "db initialized successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not initialized: "+t.toString)
      }
    })
  })

  // =========== POST /admin/initnewtables ===============================
  /*
  val postAdminInitNewTables =
    (apiOperation[ApiResponse]("postAdminInitNewTables")
      summary "Creates the schema for the new tables in this version"
      notes "Creates the tables, that are new in this exchange version, with the necessary schema in the Exchange DB. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/initnewtables" /*, operation(postAdminInitNewTables)*/ ) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    db.run(ExchangeApiTables.createNewTables.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/initnewtables result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
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
      notes "Returns a timed token that can be given to POST /admin/dropdb. The token is good for 10 minutes. Since dropping the DB tables deletes all of their data, this is a way of confirming you really want to do it. This can only be run as root."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/admin/dropdb/token", operation(getDropdbToken)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    //status_=(HttpCode.POST_OK)
    AdminDropdbTokenResponse(createToken(Role.superUser))
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

  post("/admin/dropdb", operation(postAdminDropDb)) ({
    // validateToken(BaseAccess.ADMIN, "")     // the token was generated for root, so will only work for root
    credsAndLog().authenticate("token").authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    // ApiResponse(ApiResponseType.OK, "would delete db")
    db.run(ExchangeApiTables.delete.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/dropdb result: "+xs.toString)
      xs match {
        case Success(_) => AuthCache.nodes.removeAll()     // i think we could just let the cache catch up over time, but seems better to clear it out now
          AuthCache.users.removeAll()
          AuthCache.agbots.removeAll()
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db deleted successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db not completely deleted: "+t.toString)
      }
    })
  })

  // =========== POST /admin/dropnewtables ===============================
  /*
  val postAdminDropNewTables =
    (apiOperation[ApiResponse]("postAdminDropNewTables")
      summary "Deletes the tables that are new in this version"
      notes "Deletes the tables from the Exchange DB that are new in this version. **Warning: this will delete the data too!** Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The token received from GET /admin/dropdb/token. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/dropnewtables" /*, operation(postAdminDropNewTables)*/ ) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response
    db.run(ExchangeApiTables.deleteNewTables.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/dropnewtables result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "new tables deleted successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "new tables not completely deleted: "+t.toString)
      }
    })
  })

  /*
  // =========== POST /admin/migratedb ===============================
  val postAdminMigrateDb =
    (apiOperation[ApiResponse]("postAdminMigrateDb")
      summary "Migrates the DB to a new schema"
      notes "Consider running POST /admin/upgradedb instead. Note: for now you must run POST /admin/dumptables before running this. Dumps all of the tables to files, drops the tables, creates the tables (usually with new schema), and loads the tables from the files. Can only be run by the root user."
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

  post("/admin/upgradedb", operation(postAdminUpgradeDb)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response

    // Assemble the list of db actions to: alter schema of existing tables, and create tables that are new in this version
    // val dbActions = DBIO.seq(ExchangeApiTables.alterTables, ExchangeApiTables.createNewTables)
    val dbActions = ExchangeApiTables.createNewTables
    //todo: add alterTables if its not null

    db.run(dbActions.transactionally.asTry).map({ xs =>
      logger.debug("POST /admin/upgradedb result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
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

  post("/admin/unupgradedb", operation(postAdminUnupgradeDb)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
    val resp = response

    // Assemble the list of db actions to: delete tables that are new in this version, and unalter schema changes made to existing tables
    // val dbActions = DBIO.seq(ExchangeApiTables.deleteNewTables, ExchangeApiTables.unAlterTables)
    val dbActions = ExchangeApiTables.deleteNewTables
    //todo: add unAlterTables if its not null

    // This should stop performing the actions if any of them fail. Currently intentionally not running it all as a transaction
    db.run(dbActions.asTry).map({ xs =>
      logger.debug("POST /admin/unupgradedb result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "db table schemas unupgraded successfully")
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "db table schemas not unupgraded: "+t.toString)
      }
    })
  })

  // =========== POST /admin/dumptables ===============================
  /*
  val postAdminDumpTables =
    (apiOperation[Seq[String]]("postAdminDumpTables")
      summary "Dumps all the DB tables"
      notes "Dumps all the DB tables to files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/dumptables" /*, operation(postAdminDumpTables)*/ ) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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
    db.run(NodesTQ.rows.result).map({ xs =>
      val filename = dir+"/nodes"+suffix;  logger.debug("POST /admin/dumptables "+filename+" result size: "+xs.size)
      new TableIo[NodeRow](filename).dump(xs)
      resp.setStatus(HttpCode.POST_OK);  ApiResponse(ApiResponseType.OK, "db tables dumped to "+dir+" successfully")
    })
    */
  })

  // =========== POST /admin/loadtables ===============================
  /*
  val postAdminLoadTables =
    (apiOperation[Seq[String]]("postAdminLoadTables")
      summary "Loads content for all the DB tables"
      notes "Loads content for all the DB tables from files in "+dumpDir+" in json format. Can only be run by the root user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The root username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of root. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )
      */

  post("/admin/loadtables" /*, operation(postAdminLoadTables) */) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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

  /*
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

  get("/admin/tables/:table", operation(getAdminTable)) ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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

  put("/admin/tables/:table", operation(putAdminTable)) ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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
            case Success(_) => resp.setStatus(HttpCode.PUT_OK)    // let the auth cache build up gradually
              ApiResponse(ApiResponseType.OK, table+" table restored successfully")
            case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
              ApiResponse(ApiResponseType.INTERNAL_ERROR, "table '"+table+"' not restored: "+t.toString)
          }
        })
      case _ => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Unrecognized table name: "+table+" (use the table name in the postgres DB)"))
    }
  })
  */

  // =========== GET /admin/status ===============================
  val getAdminStatus =
    (apiOperation[GetAdminStatusResponse]("getAdminStatus")
      summary "Returns status of the Exchange server"
      notes "Returns a dictionary of statuses/statistics. Can be run by any user."
      parameters(
        Parameter("username", DataType.String, Option[String]("The username. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("The password. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/admin/status", operation(getAdminStatus)) ({
    // validateUser(BaseAccess.STATUS, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.STATUS)
    val statusResp = new AdminStatus()
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
    })).map({ xs =>
      logger.debug("GET /admin/status agbotmsgs length: "+xs)
      xs match {
        case Success(v) => statusResp.numberOfAgbotMsgs = v
          statusResp.msg = "Exchange server operating normally"
        case Failure(t) => statusResp.msg = t.getMessage
      }
      statusResp.toGetAdminStatusResponse
    })
  })

  /** set 1 or more variables in the in-memory config (so it does not do the right thing in multi-node mode).
   * Intentionally not put swagger, because only used by automated tests. */
  put("/admin/config") ({
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
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
  get("/admin/gettest") ({
    // validateUser(BaseAccess.ADMIN, "")
    credsAndLog().authenticate().authorizeTo(TAction(),Access.ADMIN)
//    val resp = response

    ApiResponse(ApiResponseType.OK, "maxAgbots: "+ExchConfig.getInt("api.limits.maxAgbots"))

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

}