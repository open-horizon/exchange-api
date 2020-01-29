/** Services routes for all of the /admin api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpResponse}

import scala.concurrent.ExecutionContext
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import java.util.Properties

import akka.http.scaladsl.model.headers.RawHeader
import com.horizon.exchangeapi.tables._
import de.heikoseeberger.akkahttpjackson._
import slick.jdbc.PostgresProfile.api._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations._

import scala.util._

final case class AdminHashpwRequest(password: String) {
  require(password!=null)
}
final case class AdminHashpwResponse(hashedPassword: String)

//final case class AdminLogLevelRequest(loggingLevel: String)

final case class AdminConfigRequest(varPath: String, value: String) {
  require(varPath!=null && value!=null)
}

final case class AdminDropdbTokenResponse(token: String)

final case class GetAdminStatusResponse(msg: String, numberOfUsers: Int, numberOfNodes: Int, numberOfNodeAgreements: Int, numberOfNodeMsgs: Int, numberOfAgbots: Int, numberOfAgbotAgreements: Int, numberOfAgbotMsgs: Int, dbSchemaVersion: Int)
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

/** Case class for request body for deleting some of the IBM changes route */
final case class DeleteIBMChangesRequest(resources: List[String]) {
  def getAnyProblem: Option[String] = {
    if (resources.isEmpty) Some("resources list cannot be empty")
    else None
  }
}

/** Implementation for all of the /admin routes */
@Path("/v1/admin")
trait AdminRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def adminRoutes: Route = adminReloadRoute ~ adminHashPwRoute ~ adminGetDbTokenRoute ~ adminDropDbRoute ~ adminInitDbRoute ~ adminGetVersionRoute ~ adminGetStatusRoute ~ adminConfigRoute ~ adminClearCacheRoute ~ adminDeleteIbmChangesRoute

  // =========== POST /admin/reload ===============================
  @POST
  @Path("reload")
  @Operation(summary = "Tells the exchange reread its config file", description = """Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminReloadRoute: Route = (path("admin" / "reload") & post) {
    logger.debug("Doing POST /admin/reload")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      complete({
        ExchConfig.reload()
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("reload.successful")))
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /admin/hashpw ===============================
  @POST
  @Path("hashpw")
  @Operation(summary = "Returns a bcrypted hash of a password", description = """Takes the password specified in the request body, bcrypts it with a random salt, and returns the result. This can be useful if you want to specify root's hash pw in the config file instead of the clear pw.""",
    requestBody = new RequestBody(description = """
```
{
  "password": "pw to bcrypt"
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[AdminHashpwRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminHashPwRoute: Route = (path("admin" / "hashpw") & post & entity(as[AdminHashpwRequest])) { reqBody =>
    logger.debug("Doing POST /admin/hashpw")
    exchAuth(TAction(), Access.UTILITIES) { _ =>
      complete({
        (HttpCode.POST_OK, AdminHashpwResponse(Password.hash(reqBody.password)))
      }) // end of complete
    } // end of exchAuth
  }

  /* =========== POST /admin/loglevel ===============================
  @POST
  @Path("loglevel")
  @Operation(summary = "Sets the logging level of the exchange", description = """Dynamically set the logging level of this instance of the exchange server, taking effect immediately. If POST /admin/reload is run at a later time, and logging.level is specified in the config.json file, that will override this setting. Can only be run by the root user.""",
    requestBody = new RequestBody(description = """
```
{
  "loggingLevel": "DEBUG"   // OFF, ERROR, WARN, INFO, or DEBUG
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[AdminLogLevelRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminLogLevelRoute: Route = (path("admin/loglevel") & post & entity(as[AdminLogLevelRequest])) { reqBody =>
    logger.debug(s"Doing POST /admin/loglevel")
    exchAuthTAction(), Access.UTILITIES) { _ =>
      complete({
        if (LogLevel.validLevels.contains(req.loggingLevel)) {
          //someday: not sure yet how to change the log level while the app is running
          (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("logging.level.set")))
        } else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.logging.level", reqBody.loggingLevel)))
      }) // end of complete
    } // end of exchAuth
  }
  */

  // =========== GET /admin/dropdb/token ===============================
  @GET
  @Path("dropdb/token")
  @Operation(summary = "Gets a 1-time token for deleting the DB", description = """Returns a timed token that can be given to POST /admin/dropdb. The token is good for 10 minutes. Since dropping the DB tables deletes all of their data, this is a way of confirming you really want to do it. This can only be run as root.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[AdminDropdbTokenResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminGetDbTokenRoute: Route = (path("admin" / "dropdb" / "token") & get) {
    logger.debug("Doing GET /admin/dropdb/token")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      complete({
        (HttpCode.OK, AdminDropdbTokenResponse(createToken(Role.superUser)))
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /admin/dropdb ===============================
  @POST
  @Path("dropdb")
  @Operation(summary = "Deletes the tables from the DB", description = """Deletes the tables from the Exchange DB. **Warning: this will delete the data too!** Because this is a dangerous method, you must first get a 1-time token using GET /admin/dropdb/token, and use that to authenticate to this REST API method. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminDropDbRoute: Route = (path("admin" / "dropdb") & post) {
    logger.debug("Doing POST /admin/dropdb")
    exchAuth(TAction(), Access.ADMIN, hint = "token") { _ =>
      complete({
        db.run(ExchangeApiTables.dropDB.transactionally.asTry).map({
          case Success(v) =>
            logger.debug(s"POST /admin/dropdb result: $v")
            AuthCache.clearAllCaches(includingIbmAuth=true)
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("db.deleted")))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.deleted", t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /admin/initdb ===============================
  @POST
  @Path("initdb")
  @Operation(summary = "Creates the table schema in the DB", description = """Creates the tables with the necessary schema in the Exchange DB. This is now called at exchange startup, if necessary. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminInitDbRoute: Route = (path("admin" / "initdb") & post) {
    logger.debug("Doing POST /admin/initdb")
    ExchConfig.createRootInCache()  // need to do this before authenticating, because dropdb cleared it out (can not do this in dropdb, because it might expire)
    exchAuth(TAction(), Access.ADMIN, hint = "token") { _ =>
      complete({
        db.run(ExchangeApiTables.initDB.transactionally.asTry).map({
          case Success(v) =>
            logger.debug(s"POST /admin/initdb result: $v")
            ExchConfig.createRoot(db)         // initialize the users table with the root user from config.json
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("db.init")))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.init", t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== GET /admin/version ===============================
  @GET
  @Path("version")
  @Operation(summary = "Returns the version of the Exchange server", description = """Returns the version of the Exchange server as a simple string (no JSON or quotes). Can be run by anyone.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[String]))))))
  def adminGetVersionRoute: Route = (path("admin" / "version") & get) {
    logger.debug("Doing POST /admin/version")
    val version = ExchangeApi.adminVersion() + "\n"
    //complete({ (HttpCode.POST_OK, version) }) // <- this sends it as json, so with double quotes around it and \n explicitly in the string
    complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, version)).addHeader(RawHeader("Cache-Control", "no-cache")).addHeader(RawHeader("Pragma", "no-cache")))
  }

  // =========== GET /admin/status ===============================
  @GET
  @Path("status")
  @Operation(summary = "Returns status of the Exchange server", description = """Returns a dictionary of statuses/statistics. Can be run by any user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetAdminStatusResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminGetStatusRoute: Route = (path("admin" / "status") & get) {
    logger.debug("Doing GET /admin/status")
    exchAuth(TAction(), Access.STATUS) { _ =>
      complete({
        val statusResp = new AdminStatus()
        //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
        db.run(UsersTQ.rows.length.result.asTry.flatMap({
          case Success(v) => statusResp.numberOfUsers = v
            NodesTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodes = v
            AgbotsTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbots = v
            NodeAgreementsTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeAgreements = v
            AgbotAgreementsTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbotAgreements = v
            NodeMsgsTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeMsgs = v
            AgbotMsgsTQ.rows.length.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbotMsgs = v
            SchemaTQ.getSchemaVersion.result.asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        })).map({
          case Success(v) => statusResp.dbSchemaVersion = v.head
            statusResp.msg = "Exchange server operating normally"
            (HttpCode.OK, statusResp.toGetAdminStatusResponse)
          case Failure(t) => statusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, statusResp.toGetAdminStatusResponse)
        })
      }) // end of complete
    } // end of exchAuth
  }

  /** set 1 or more variables in the in-memory config (does not affect all instances in multi-node mode).
   * Intentionally not put swagger, because only used by automated tests. */
  def adminConfigRoute: Route = (path("admin" / "config") & put & entity(as[AdminConfigRequest])) { reqBody =>
    logger.debug(s"Doing POST /admin/config")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      complete({
        val props = new Properties()
        props.setProperty(reqBody.varPath, reqBody.value)
        ExchConfig.mod(props)
        (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("config.value.set")))
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /admin/clearAuthCaches ===============================
  def adminClearCacheRoute: Route = (path("admin" / "clearauthcaches") & post) {
    logger.debug("Doing POST /admin/clearauthcaches")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      complete({
        //todo: ensure other client requests are not updating the cache at the same time
        AuthCache.clearAllCaches(includingIbmAuth=true)
        (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("cache.cleared")))
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== DELETE /orgs/IBM/changes/all ================================ */
  // This route is just for unit testing as a way to clean up the changes table once testing has completed
  // Otherwise the changes table gets clogged with entries in the IBM org from testing
  def adminDeleteIbmChangesRoute: Route = (path("orgs" / "IBM" / "changes" / "cleanup") & delete & entity(as[DeleteIBMChangesRequest])) { reqBody =>
    logger.debug("Doing POST /orgs/IBM/changes/cleanup")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val resourcesSet = reqBody.resources.toSet
          val action = ResourceChangesTQ.rows.filter(_.orgId === "IBM").filter(_.id inSet resourcesSet).delete
          db.run(action.transactionally.asTry).map({
            case Success(v) =>
              logger.debug(s"Deleted specified IBM org entries in changes table ONLY FOR UNIT TESTS: $v")
              if (v > 0) (HttpCode.DELETED, ApiResponse(ApiRespType.OK, "IBM changes deleted"))
              else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", "IBM")))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, "IBM org changes not deleted: " + t.toString))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

}