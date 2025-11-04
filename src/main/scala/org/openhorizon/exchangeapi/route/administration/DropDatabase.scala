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


@Path("/v1/admin")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait DropDatabase extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== POST /admin/dropdb ===============================
  @POST
  @Path("dropdb")
  @Operation(summary = "Deletes the tables from the database",
    description = """Deletes the tables from the Exchange database. **Warning: this will delete the data too!** Because this is a dangerous method, you must first get a one-time token using GET /admin/dropdb/token, and use that to authenticate to this REST API method. Can only be run by the root User.""",
    responses =
      Array(new responses.ApiResponse(responseCode = "201",
                                      description = "response body",
                                      content =
                                        Array(new Content(mediaType = "application/json",
                                                          schema = new Schema(implementation = classOf[ApiResponse])))),
            new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
            new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def postDropDB(@Parameter(hidden = true) identity: Identity2): Route = {
    logger.debug(s"POST /admin/dropdb - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      db.run(ExchangeApiTables.dropDB.transactionally.asTry)
        .map({
          case Success(v) =>
            logger.debug(s"POST /admin/dropdb result: $v")
            // TODO: AuthCache.clearAllCaches(includingIbmAuth = true)
            (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("db.deleted")))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("db.not.deleted", t.toString))
          case Failure(t) =>
            (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.deleted", t.toString)))
        })
    }) // end of complete
  }
  
  
  def dropDB(identity: Identity2): Route =
    path("admin" / "dropdb") {
      post {
        exchAuth(TAction(), Access.ADMIN, hint = "token", validIdentity = identity) {
          _ =>
            postDropDB(identity)
        }
      }
    }
}
