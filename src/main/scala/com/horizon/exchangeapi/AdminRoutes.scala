/** Services routes for all of the /admin api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
//import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.headers.`Content-Type`
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route

import java.util.Properties

import com.horizon.exchangeapi.tables._
import de.heikoseeberger.akkahttpjackson._
//import org.json4s._
//import org.json4s.jackson.JsonMethods._
import slick.jdbc.PostgresProfile.api._

import io.swagger.v3.oas.annotations.parameters.RequestBody
//import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
//import io.swagger.v3.oas.annotations.responses.ApiResponse
//import io.swagger.v3.oas.annotations.{ Operation, Parameter }
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext.Implicits.global

import scala.util._

final case class AdminHashpwRequest(password: String)
final case class AdminHashpwResponse(hashedPassword: String)

final case class AdminLogLevelRequest(loggingLevel: String)

final case class AdminConfigRequest(varPath: String, value: String)

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
class AdminRoutes(implicit val system: ActorSystem) extends JacksonSupport with AuthenticationSupport {
  def db: Database = ExchangeApiApp.getDb
  lazy implicit val logger: LoggingAdapter = Logging(system, classOf[OrgsRoutes])
  //protected implicit def jsonFormats: Formats

  def routes: Route = adminReloadRoute ~ adminHashPwRoute ~ adminGetDbTokenRoute ~ adminDropDbRoute ~ adminInitDbRoute ~ adminGetVersionRoute ~ adminGetStatusRoute ~ adminConfigRoute ~ adminClearCacheRoute ~ adminDeleteIbmChangesRoute

  // =========== POST /admin/reload ===============================
  @POST
  @Path("reload")
  @Operation(summary = "Tells the exchange reread its config file", description = """Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminReloadRoute: Route = (post & path("admin" / "reload") & extractCredentials) { creds =>
    logger.debug("Doing POST /admin/reload")
    auth(creds, TAction(), Access.ADMIN) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          ExchConfig.reload()
          (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("reload.successful")))
        }) // end of complete
    } // end of auth match
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
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminHashPwRoute: Route = (post & path("admin" / "hashpw") & extractCredentials) { creds =>
    logger.debug("Doing POST /admin/hashpw")
    auth(creds, TAction(), Access.UTILITIES) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        entity(as[AdminHashpwRequest]) { req =>
          complete({
            (HttpCode.POST_OK, AdminHashpwResponse(Password.hash(req.password)))
          }) // end of complete
        } // end of entity
    } // end of auth match
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
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminLogLevelRoute: Route = (post & path("admin/loglevel") & extractCredentials) { creds =>
    logger.debug(s"Doing POST /admin/loglevel")
    auth(creds, TAction(), Access.UTILITIES) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        entity(as[AdminLogLevelRequest]) { req =>
          complete({
            if (LogLevel.validLevels.contains(req.loggingLevel)) {
              //todo: not sure yet how to change the log level while the app is running
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("logging.level.set")))
            } else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.logging.level", req.loggingLevel)))
          }) // end of complete
        } // end of entity
    } // end of auth match
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
  def adminGetDbTokenRoute: Route = (get & path("admin" / "dropdb" / "token") & extractCredentials) { creds =>
    logger.debug("Doing GET /admin/dropdb/token")
    auth(creds, TAction(), Access.ADMIN) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          (HttpCode.OK, AdminDropdbTokenResponse(createToken(Role.superUser)))
        }) // end of complete
    } // end of auth match
  }

  // =========== POST /admin/dropdb ===============================
  @POST
  @Path("dropdb")
  @Operation(summary = "Deletes the tables from the DB", description = """Deletes the tables from the Exchange DB. **Warning: this will delete the data too!** Because this is a dangerous method, you must first get a 1-time token using GET /admin/dropdb/token, and use that to authenticate to this REST API method. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminDropDbRoute: Route = (post & path("admin" / "dropdb") & extractCredentials) { creds =>
    logger.debug("Doing POST /admin/dropdb")
    auth(creds, TAction(), Access.ADMIN, hint = "token") match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
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
    } // end of auth match
  }

  // =========== POST /admin/initdb ===============================
  @POST
  @Path("initdb")
  @Operation(summary = "Creates the table schema in the DB", description = """Creates the tables with the necessary schema in the Exchange DB. This is now called at exchange startup, if necessary. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminInitDbRoute: Route = (post & path("admin" / "initdb") & extractCredentials) { creds =>
    logger.debug("Doing POST /admin/initdb")
    ExchConfig.createRootInCache()  // need to do this before authenticating, because dropdb cleared it out (can not do this in dropdb, because it might expire)
    auth(creds, TAction(), Access.ADMIN, hint = "token") match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
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
    } // end of auth match
  }

  // =========== GET /admin/version ===============================
  @GET
  @Path("version")
  @Operation(summary = "Returns the version of the Exchange server", description = """Returns the version of the Exchange server as a simple string (no JSON or quotes). Can be run by anyone.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[String]))))))
  def adminGetVersionRoute: Route = (get & path("admin" / "version")) {
    logger.debug("Doing POST /admin/version")
    val version = ExchangeApiAppMethods.adminVersion() + "\n"
    //complete({ (HttpCode.POST_OK, version) }) // <- this sends it as json, so with double quotes around it and \n explicitly in the string
    //complete(HttpCode.POST_OK, List(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)), ExchangeApiAppMethods.adminVersion() + "\n") // <- this tells me i can't explicitly set header Content-Type
    complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, version)))
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
  def adminGetStatusRoute: Route = (get & path("admin" / "status") & extractCredentials) { creds =>
    logger.debug("Doing GET /admin/status")
    auth(creds, TAction(), Access.STATUS) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          val statusResp = new AdminStatus()
          //TODO: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
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
    } // end of auth match
  }

  /** set 1 or more variables in the in-memory config (so it does not do the right thing in multi-node mode).
   * Intentionally not put swagger, because only used by automated tests. */
  def adminConfigRoute: Route = (put & path("admin" / "config") & extractCredentials) { creds =>
    logger.debug(s"Doing POST /admin/config")
    auth(creds, TAction(), Access.ADMIN) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        entity(as[AdminConfigRequest]) { req =>
          complete({
            val props = new Properties()
            props.setProperty(req.varPath, req.value)
            ExchConfig.mod(props)
            (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("config.value.set")))
          }) // end of complete
        } // end of entity
    } // end of auth match
  }

  // =========== POST /admin/clearAuthCaches ===============================
  def adminClearCacheRoute: Route = (post & path("admin" / "clearauthcaches") & extractCredentials) { creds =>
    logger.debug("Doing POST /admin/clearauthcaches")
    auth(creds, TAction(), Access.ADMIN) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        complete({
          //todo: ensure other client requests are not updating the cache at the same time
          AuthCache.clearAllCaches(includingIbmAuth=true)
          (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("cache.cleared")))
        }) // end of complete
    } // end of auth match
  }

  /* ====== DELETE /orgs/IBM/changes/all ================================ */
  // This route is just for unit testing as a way to clean up the changes table once testing has completed
  // Otherwise the changes table gets clogged with entries in the IBM org from testing
  def adminDeleteIbmChangesRoute: Route = (delete & path("orgs" / "IBM" / "changes" / "cleanup") & extractCredentials) { creds =>
    logger.debug("Doing POST /orgs/IBM/changes/cleanup")
    auth(creds, TAction(), Access.ADMIN) match {
      case Failure(t) => reject(AuthRejection(t))
      case Success(_) =>
        entity(as[DeleteIBMChangesRequest]) { req =>
          validate(req.getAnyProblem.isEmpty, "Problem in request body") {
            complete({
              val resourcesSet = req.resources.toSet
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
          } // end of validate
        } // end of entity
    } // end of auth match
  }

}