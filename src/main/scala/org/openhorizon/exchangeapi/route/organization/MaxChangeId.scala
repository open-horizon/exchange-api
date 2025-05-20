package org.openhorizon.exchangeapi.route.organization

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.{complete, path, _}
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, TAction}
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api.actionBasedSQLInterpolation
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait MaxChangeId extends JacksonSupport with AuthenticationSupport{
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /changes/maxchangeid ================================
  @GET
  @Path("changes/maxchangeid")
  @Operation(summary = "Returns the max changeid of the resource changes", description = "Returns the max changeid of the resource changes. Can be run by the root user, organization admins, or any node or agbot.",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[MaxChangeIdResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def getMaxChangeId(@Parameter(hidden = true) identity: Identity2): Route = {
    complete({
      // Shortcut to grabbing the last allocated ChangeId.
      db.run(sql"SELECT last_value FROM public.resourcechanges_changeid_seq;".as[(Long)].headOption)
        .map({
          currentChange =>
            logger.debug("GET /changes/maxchangeid result: " + currentChange)
            (StatusCodes.OK, MaxChangeIdResponse(currentChange.getOrElse(0)))
        })
    })
  }
  
  def maxChangeId(identity: Identity2): Route =
    path("changes" / "maxchangeid") {
      get {
        logger.debug("Doing GET /changes/maxchangeid")
        exchAuth(TAction(), Access.MAXCHANGEID, validIdentity = identity) {
          _ =>
            getMaxChangeId(identity)
        }
      }
    }
}
