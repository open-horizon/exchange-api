package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, TAction}
import org.openhorizon.exchangeapi.table.ExchangeApiTables
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling}
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
  def postInitializeDB(@Parameter(hidden = true) identity: Identity2): Route = {
    logger.debug(s"POST /admin/initdb - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete ({
      db.run(ExchangeApiTables.initDB.transactionally.asTry)
        .map({
          case Success(v) =>
            logger.debug(s"POST /admin/initdb result: $v")
            ExchangeApiTables.upgradeDb(db = db)(logger = logger, executionContext = executionContext) // initialize the users table with the root user from config.json
            (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("db.init")))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("db.not.init", t.toString))
          case Failure(t) =>
            (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.init", t.toString)))
        })
    })
  }
  
  def initializeDB(identity: Identity2): Route =
    path("admin" / "initdb") {
      post {
        // TODO: AuthCache.createRootInCache() // need to do this before authenticating, because dropdb cleared it out (can not do this in dropdb, because it might expire)
        exchAuth(TAction(), Access.ADMIN, hint = "token", validIdentity = identity) {
          _ =>
            postInitializeDB(identity)
        }
      }
    }
}
