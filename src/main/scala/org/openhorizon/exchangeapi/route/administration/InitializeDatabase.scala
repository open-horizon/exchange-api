package org.openhorizon.exchangeapi.route.administration

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives.{complete, path, pathPrefix, post, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, responses}
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import jakarta.ws.rs.{POST, Path}
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangeApiTables, ExchangePosgtresErrorHandling, HttpCode, TAction}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/admin/initdb")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait InitializeDatabase extends JacksonSupport with AuthenticationSupport{
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /admin/initdb ===============================
  @POST
  @Operation(summary = "Creates the table schema in the DB",
             description = """Creates the tables with the necessary schema in the Exchange DB. This is now called at exchange startup, if necessary. Can only be run by the root user.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "201", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def postInitializeDB: Route = {
    logger.debug("Doing POST /admin/initdb")
    ExchConfig.createRootInCache() // need to do this before authenticating, because dropdb cleared it out (can not do this in dropdb, because it might expire)
    complete ({
      db.run(ExchangeApiTables.initDB.transactionally.asTry)
        .map({
          case Success(v) =>
            logger.debug(s"POST /admin/initdb result: $v")
            ExchConfig.createRoot(db) // initialize the users table with the root user from config.json
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("db.init")))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("db.not.init", t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.init", t.toString)))
        })
    })
  }
  
  val initializeDB: Route =
    path("admin" / "initdb") {
      post {
        exchAuth(TAction(), Access.ADMIN, hint = "token") {
          _ =>
            postInitializeDB
        }
      }
    }
}
