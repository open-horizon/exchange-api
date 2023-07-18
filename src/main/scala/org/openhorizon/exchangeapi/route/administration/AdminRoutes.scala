/** Services routes for all of the /admin api methods. */
package org.openhorizon.exchangeapi.route.administration

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.{Access, ApiResponse, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangeApi, ExchangeApiTables, ExchangePosgtresErrorHandling, HttpCode, Password, Role, TAction}
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{GET, POST, Path}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.organization.ResourceChangesTQ
import org.openhorizon.exchangeapi.{Access, ApiRespType, AuthCache, AuthenticationSupport, ExchangeApi, ExchangeApiTables, Role}
import slick.jdbc.PostgresProfile.api._

import java.util.Properties
import scala.concurrent.ExecutionContext
import scala.util._


//final case class AdminLogLevelRequest(loggingLevel: String)


/** Implementation for all of the /admin routes */
@Path("/v1/admin")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait AdminRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def adminRoutes: Route = /*adminReloadRoute ~
                           adminHashPwRoute ~
                           adminGetDbTokenRoute ~
                           adminDropDbRoute ~
                           adminInitDbRoute ~
                           adminGetVersionRoute ~
                           adminGetStatusRoute ~
                           adminGetOrgStatusRoute ~
                           adminConfigRoute ~
                           adminClearCacheRoute ~*/
                           adminDeleteOrgChangesRoute

  /*
  // =========== POST /admin/reload ===============================
  @POST
  @Path("reload")
  @Operation(summary = "Tells the exchange reread its config file", description = """Directs the exchange server to reread /etc/horizon/exchange/config.json and continue running with those new settings. Can only be run by the root user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
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
  @Operation(
    summary = "Returns a bcrypted hash of a password",
    description = "Takes the password specified in the request body, bcrypts it with a random salt, and returns the result. This can be useful if you want to specify root's hash pw in the config file instead of the clear pw.",
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "password": "pw to bcrypt"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[AdminHashpwRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      )
    )
  )
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
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[AdminDropdbTokenResponse])))),
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
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
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
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("db.not.deleted", t.toString))
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
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
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
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("db.not.init", t.toString))
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
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String]))))))
  def adminGetVersionRoute: Route = (path("admin" / "version") & get) {
    logger.debug("Doing POST /admin/version")
    val version: String = ExchangeApi.adminVersion() + "\n"
    complete({ (HttpCode.POST_OK, version) }) // <- this sends it as json, so with double quotes around it and \n explicitly in the string
    // complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, version)))
  }

  // =========== GET /admin/status ===============================
  @GET
  @Path("status")
  @Operation(summary = "Returns status of the Exchange server", description = """Returns a dictionary of statuses/statistics. Can be run by any user.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetAdminStatusResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminGetStatusRoute: Route = (path("admin" / "status") & get) {
    logger.debug("Doing GET /admin/status")
    exchAuth(TAction(), Access.STATUS) { _ =>
      complete({
        val statusResp = new AdminStatus()
        //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
        db.run(UsersTQ.length.result.asTry.flatMap({
          case Success(v) => statusResp.numberOfUsers = v
            NodesTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodes = v
            AgbotsTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbots = v
            NodeAgreementsTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeAgreements = v
            AgbotAgreementsTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbotAgreements = v
            NodeMsgsTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeMsgs = v
            AgbotMsgsTQ.length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfAgbotMsgs = v
            SchemaTQ.getSchemaVersion.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => statusResp.dbSchemaVersion = v.head
            statusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, statusResp.toGetAdminStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (t.getMessage.contains("An I/O error occurred while sending to the backend")) (HttpCode.BAD_GW, statusResp.toGetAdminStatusResponse)
            else (HttpCode.INTERNAL_ERROR, statusResp.toGetAdminStatusResponse)
          case Failure(t) => statusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, statusResp.toGetAdminStatusResponse)
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== GET /admin/orgstatus ===============================
  @GET
  @Path("orgstatus")
  @Operation(summary = "Returns the org-specific status of the Exchange server", description = """Returns a dictionary of statuses/statistics. Can be run by superuser, hub admins, and org admins.""",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetAdminOrgStatusResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminGetOrgStatusRoute: Route = (path("admin" / "orgstatus") & get) {
    logger.debug("Doing GET /admin/orgstatus")
    exchAuth(TAction(), Access.ORGSTATUS) { _ =>
      complete({
        val orgStatusResp = new AdminOrgStatus()
        //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
        val q = for {
          n <- NodesTQ.groupBy(_.orgid)
        } yield (n._1, n._2.length) // this should returin [orgid, num of nodes in that orgid]
        db.run(q.result.asTry).map({
          case Success(nodes) =>
            // nodes : Seq[(String, Int)]
            orgStatusResp.nodesByOrg = nodes.toMap
            orgStatusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, orgStatusResp.toGetAdminOrgStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            orgStatusResp.msg = t.getMessage
            if (t.getMessage.contains("An I/O error occurred while sending to the backend")) (HttpCode.BAD_GW, orgStatusResp.toGetAdminOrgStatusResponse)
            else (HttpCode.INTERNAL_ERROR, orgStatusResp.toGetAdminOrgStatusResponse)
          case Failure(t) => orgStatusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, orgStatusResp.toGetAdminOrgStatusResponse)
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
   */

  /* ====== DELETE /orgs/<orgid>/changes/cleanup ================================ */
  // This route is just for unit testing as a way to clean up the changes table once testing has completed
  // Otherwise the changes table gets clogged with entries in the orgs from testing
  def adminDeleteOrgChangesRoute: Route = (path("orgs" / Segment / "changes" / "cleanup") & delete & entity(as[DeleteOrgChangesRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgId/changes/cleanup")
    exchAuth(TAction(), Access.ADMIN) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val resourcesSet: Set[String] = reqBody.resources.toSet
          var action = ResourceChangesTQ.filter(_.orgId === orgId).filter(_.id inSet resourcesSet).delete
          if (reqBody.resources.isEmpty) action = ResourceChangesTQ.filter(_.orgId === orgId).delete
          db.run(action.transactionally.asTry).map({
            case Success(v) =>
              logger.debug(s"Deleted specified $orgId org entries in changes table ONLY FOR UNIT TESTS: $v")
              if (v > 0) (HttpCode.DELETED, ApiResponse(ApiRespType.OK, s"$orgId changes deleted"))
              else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, s"$orgId org changes not deleted: " + t.toString)
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, s"$orgId org changes not deleted: " + t.toString))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

}