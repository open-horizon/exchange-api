package org.openhorizon.exchangeapi.route.apikey

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media._
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.table.apikey.ApiKeysTQ
import org.openhorizon.exchangeapi.utility._
import org.openhorizon.exchangeapi.table.apikey.ApiKeyMetadata
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util._

@Path("/v1/orgs/{orgid}/apikeys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "API Key")
trait OrgApiKeys extends JacksonSupport with AuthenticationSupport {

  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  // ========== GET /orgs/{orgid}/apikeys ============================


  def getOrgApiKeys(@Parameter(hidden = true) identity: Identity,
                  @Parameter(hidden = true) orgid: String): Route = {
  onSuccess(db.run(OrgsTQ.getOrgid(orgid).result)) {
    case Nil =>
      complete(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgid)))
    case _ =>
      complete {
        db.run(ApiKeysTQ.getByOrg(orgid).result).map { rows =>
          val keys = rows.map(_.toMetadata)
   (StatusCodes.OK, GetOrgApiKeysResponse(keys))   
        }
      }
  }
}


  @GET
  @Operation(
    summary = "Get all API keys for an org",
    description = "Returns all API keys for the given organization. Must be org admin.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization ID")
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "API key metadata",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetOrgApiKeysResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "Unauthorized"),
      new responses.ApiResponse(responseCode = "403", description = "Forbidden")
    )
  )
  val orgApiKeys: Route =
  path("orgs" / Segment / "apikeys") { orgid =>
    onSuccess(db.run(OrgsTQ.getOrgid(orgid).result)) {
      case Nil =>
        complete(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgid)))
      case _ =>
        exchAuth(TOrg(orgid), Access.READ) { identity =>
          if (identity.getOrg == orgid && identity.isAdmin)
            getOrgApiKeys(identity, orgid)
          else
            complete(HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("access.denied.not.org.admin")))
        }
    }
  }

}
// ==== Response model ====

final case class GetOrgApiKeysResponse(apikeys: Seq[ApiKeyMetadata])
