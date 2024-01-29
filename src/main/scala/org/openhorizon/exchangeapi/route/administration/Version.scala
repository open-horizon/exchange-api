package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, responses}
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.utility.HttpCode
import org.openhorizon.exchangeapi.{ExchangeApi}
import org.openhorizon.exchangeapi.auth.AuthenticationSupport
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
