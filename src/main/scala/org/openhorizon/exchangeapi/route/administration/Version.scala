package org.openhorizon.exchangeapi.route.administration

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, get, path, pathPrefix, _}
import akka.http.scaladsl.server.{Directives, Route}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, responses}
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.{AuthenticationSupport, ExchangeApi, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1/admin/version")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait Version extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== GET /admin/version ===============================
  @GET
  @Operation(summary = "Returns the version of the Exchange server",
             description = """Returns the version of the Exchange server as a simple string (no JSON or quotes). Can be run by anyone.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200",
                                               description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[String]))))))
  def getVersion: Route =
    get {
      logger.debug("Doing POST /admin/version")
      val version: String = ExchangeApi.adminVersion() + "\n"
   
      complete(HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, version)))
    }
  
  
  val version: Route =
    path("admin" / "version") {
      getVersion
    }
}
