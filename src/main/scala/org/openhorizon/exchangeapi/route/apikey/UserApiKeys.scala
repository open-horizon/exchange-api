package org.openhorizon.exchangeapi.route.apikey

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media._
import jakarta.ws.rs.{GET, POST, DELETE, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeysTQ, ApiKeyRow}
import org.openhorizon.exchangeapi.utility._
import java.util.UUID
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.ExecutionContext
import scala.util._
import _root_.org.openhorizon.exchangeapi.utility.ApiKeyUtils
import org.openhorizon.exchangeapi.utility.HttpCode
import io.swagger.v3.oas.annotations.parameters.RequestBody

@Path("/v1/orgs/{orgid}/users/{username}/apikeys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "API Key")
trait UserApiKeys extends JacksonSupport with AuthenticationSupport {

  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  // === GET /v1/orgs/{orgid}/users/{username}/apikeys ===
  def getUserApiKeys(identity: Identity, orgid: String, username: String): Route = complete {
  val fullId = s"$orgid/$username"
  db.run(ApiKeysTQ.getByUser(fullId).result).map { rows =>
    val keys = rows.map(_.toMetadata)
    val code: StatusCode = if (keys.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
    (code, GetUserApiKeysResponse(keys))
  }
}

  // === POST /v1/orgs/{orgid}/users/{username}/apikeys ===
    @POST
    @Operation(
  summary = "Create a new API key for a user",
  description = "Creates a new API key for the specified user. Can be call by the user or org admin.",
  parameters = Array(
    new Parameter(name = "orgid", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username")
  ),
  requestBody = new RequestBody(
    required = true,
    content = Array(new Content(
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PostApiKeyRequest])
    ))
  ),
  responses = Array(
    new responses.ApiResponse(responseCode = "201", description = "API key created",
      content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostApiKeyResponse])))),
    new responses.ApiResponse(responseCode = "400", description = "Bad Request"),
    new responses.ApiResponse(responseCode = "403", description = "Forbidden"),
  )
)
  def postUserApiKey(identity: Identity, orgid: String, username: String): Route = {
    entity(as[PostApiKeyRequest]) { body =>
      val fullId = s"$orgid/$username"
      val rawValue = ApiKeyUtils.generateApiKeyValue()
      val hashedValue = ApiKeyUtils.sha256Hash(rawValue)
      val keyId = ApiKeyUtils.generateApiKeyId()
      val row = ApiKeyRow(orgid, keyId, fullId, body.description, hashedValue)

      complete {
        db.run(ApiKeysTQ.insert(row).asTry).map {
          case Success(_) =>
            (HttpCode.POST_OK, PostApiKeyResponse(keyId, body.description, username, rawValue))
          case Failure(_) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("apikey.creation.failed")))
        }
      }
    }
  }

  // === DELETE /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} ===
  def deleteUserApiKey(identity: Identity, orgid: String, username: String, keyid: String): Route = complete {
    db.run(ApiKeysTQ.deleteById(keyid).delete.map {
      case 0 => StatusCodes.NoContent
      case _ => StatusCodes.NoContent
    })
  }

  // === GET /v1/orgs/{orgid}/users/{username}/apikeys/{keyid} ===
  def getUserApiKeyById(identity: Identity, orgid: String, username: String, keyid: String): Route = complete {
    db.run(ApiKeysTQ.getById(keyid).result).map {
      case Seq(row) => (StatusCodes.OK, row.toMetadata)
      case _ => (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("apikey.not.found")))
    }
  }

  val userApiKeys: Route = pathPrefix("orgs" / Segment / "users" / Segment / "apikeys") { (orgid, username) =>
    pathEndOrSingleSlash {
      post {                                 //Should create enumeration later
        exchAuth(TUser(s"$orgid/$username"), Access.WRITE) { identity =>
        if (!(identity.creds.id == s"$orgid/$username" || (identity.getOrg == orgid && identity.isAdmin)))
            complete(HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, "Only the user or org admin can create API keys."))
          else
            postUserApiKey(identity, orgid, username)
        }
      } ~
      get {
        exchAuth(TUser(s"$orgid/$username"), Access.READ) { identity =>
          if (!(identity.creds.id == s"$orgid/$username" || (identity.getOrg == orgid && identity.isAdmin)))
            complete(HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, "Only the user or org admin can view API keys."))
          else
            getUserApiKeys(identity, orgid, username)
        }
      }
    } ~
    path(Segment) { keyid =>
      get {
        exchAuth(TUser(s"$orgid/$username"), Access.READ) { identity =>
           if (!(identity.creds.id == s"$orgid/$username" || (identity.getOrg == orgid && identity.isAdmin)))
            complete(HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, "Only the user or org admin can view the API key."))
          else

          getUserApiKeyById(identity, orgid, username, keyid)
        }
      } ~
      delete {
        exchAuth(TUser(s"$orgid/$username"), Access.WRITE) { identity =>
           if (!(identity.creds.id == s"$orgid/$username" || (identity.getOrg == orgid && identity.isAdmin)))
            complete(HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, "Only the user or org admin can delete API keys."))
          else
          deleteUserApiKey(identity, orgid, username, keyid)
        }
      }
    }
  }
}

// ==== Request & Response Models ====
final case class PostApiKeyRequest(description: String)
final case class PostApiKeyResponse(id: String, description: String, user: String, value: String)
final case class GetUserApiKeysResponse(apikeys: Seq[ApiKeyMetadata])
