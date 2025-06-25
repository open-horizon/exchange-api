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
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceIdentity, cacheResourceOwnership, getOwnerOfResource, myUserPassAuthenticator, routes}
import org.openhorizon.exchangeapi.table.apikey.{ApiKeyMetadata, ApiKeysTQ, ApiKeyRow}
import org.openhorizon.exchangeapi.table.user.{User, UserRow, UsersTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility._
import java.sql.Timestamp
import java.time.ZoneId
import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import io.swagger.v3.oas.annotations.parameters.RequestBody
import scalacache.modes.scalaFuture._
import slick.jdbc.PostgresProfile.api._

@Path("/v1/orgs/{organization}/users/{username}/apikeys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "user/apikey")
trait UserApiKeys extends JacksonSupport with AuthenticationSupport {

  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
 
  // === POST /v1/orgs/{organization}/users/{username}/apikeys ===
  @POST
  @Operation(
  summary = "Create a new API key for a user",
  description = "Creates a new API key for the specified user. Can be called by the user or org admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username")
  ),
  requestBody = new RequestBody(
    required = true,
    content = Array(new Content(
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PostApiKeyRequest]),
      examples = Array(
        new ExampleObject(
          value = """{
  "description": "Test API key for user"
}""")
      )
    ))
  ),
  responses = Array(
    new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
      content = Array(new Content(
        mediaType = "application/json", 
        schema = new Schema(implementation = classOf[PostApiKeyResponse]),
        examples = Array(
          new ExampleObject(
            value = """{
  "id": "string",
  "description": "string",
  "owner": "string",
  "value": "string",
  "lastUpdated": "string"
}""")
        )
      ))),
    new responses.ApiResponse(responseCode = "400", description = "bad input"),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied")
  )
)
  def postUserApiKey(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) username: String,
                    @Parameter(hidden = true) resourceUuidOpt: Option[UUID]): Route = {
      entity(as[PostApiKeyRequest]) { body =>
        val ownerStr = s"$organization/$username"
        val sha256Token = ApiKeyUtils.generateApiKeyHashedValue()
        val argon2ForDb = Password.hash(sha256Token)
        val keyId = ApiKeyUtils.generateApiKeyId()
        val timestamp: java.sql.Timestamp = ApiTime.nowUTCTimestamp
        
        resourceUuidOpt match {
          case Some(userUuid) =>
            // logger.debug(s"[postUserApiKey] Using UUID: $userUuid for user $ownerStr")
            complete {
              val userQuery = Compiled {
                UsersTQ.filter(user => (user.user === userUuid &&
                                      user.organization === organization &&
                                      user.username === username))
                      .filterIf(identity.isStandardUser)(users => users.user === identity.identifier.get)
                      .filterIf(identity.isOrgAdmin)(users => !users.isHubAdmin && users.organization === identity.organization && !(users.organization === "root" && users.username === "root"))
                      .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin) && !(users.organization === "root" && users.username === "root"))
                      .take(1).length
              }
              
              val createApiKey: DBIOAction[Unit, NoStream, Effect.Read with Effect.Write] =
                for {
                  userCount <- userQuery.result
                  _ <- if (userCount > 0) {
                        DBIO.successful(())
                      } else {
                        DBIO.failed(new ClassNotFoundException())
                      }
                  _ <- ApiKeysTQ += ApiKeyRow(
                        orgid = organization,
                        id = keyId,
                        user = userUuid,
                        description = body.description,
                        hashedKey = argon2ForDb,
                        createdAt = timestamp,
                        createdBy = identity.identifier.get,
                        modifiedAt = timestamp,
                        modifiedBy = identity.identifier.get
                      )
                  // _ <- ResourceChangesTQ += ResourceChange(
                  //       0L,
                  //       organization,
                  //       keyId.toString,
                  //       ResChangeCategory.APIKEY,
                  //       public = false,
                  //       ResChangeResource.APIKEY,
                  //       ResChangeOperation.CREATED
                  //     ).toResourceChangeRow
                } yield ()
              
              db.run(createApiKey.transactionally).map { _ =>
                val response = PostApiKeyResponse(
                  id = keyId.toString,
                  description = body.description,
                  owner = ownerStr,
                  value = sha256Token,
                  lastUpdated = timestamp.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString
                )
                (HttpCode.POST_OK, response)
              }.recover {
                case ex: ClassNotFoundException =>
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("user.not.found", s"$organization/$username")))
                case ex =>
                  logger.error(s"Failed to create API key for $organization/$username", ex)
                  (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("apikey.creation.failed")))
              }
            }
          
          case None =>
            complete(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, "User not found"))
        }
      }
    }

  // === DELETE /v1/orgs/{organization}/users/{username}/apikeys/{keyid} ===
  @DELETE
  @Path("/{keyid}")
  @Operation(
  summary = "Delete an API key for a user",
  description = "Deletes API key with the given ID. Must be called by the user themselves (if they are the owner) or an organization admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username"),
    new Parameter(name = "keyid", in = ParameterIn.PATH, required = true, description = "API key ID to delete")
  ),
  responses = Array(
    new responses.ApiResponse(responseCode = "204", description = "deleted"),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied"),
    new responses.ApiResponse(responseCode = "404", description = "not found"),
    new responses.ApiResponse(responseCode = "500", description = "internal server error")
  )
)
  def deleteUserApiKey(@Parameter(hidden = true) identity: Identity2,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) username: String,
                      @Parameter(hidden = true) keyidStr: String,
                      @Parameter(hidden = true) resourceUuidOpt: Option[UUID]): Route = complete {
    Try(UUID.fromString(keyidStr)) match {
      case Failure(_) =>
        Future.successful((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, "Invalid UUID format for API key ID")))
      case Success(keyid) =>
        resourceUuidOpt match {
          case Some(userUuid) =>
            val deleteQuery = Compiled {
              ApiKeysTQ.filter(k => k.id === keyid && k.user === userUuid && k.orgid === organization)
                .filterIf(identity.isStandardUser)(k => k.user === identity.identifier.get)
                .filterIf(identity.isOrgAdmin)(k => k.orgid === identity.organization)
                .filterIf(identity.isHubAdmin)(k => UsersTQ.filter(u => u.user === k.user && ((u.isHubAdmin || u.isOrgAdmin) && !(u.organization === "root" && u.username === "root"))).exists)
            }
            
            db.run((for {
              deleted <- deleteQuery.delete
              // _ <- if (deleted > 0) {
              //       ResourceChangesTQ += ResourceChange(
              //         0L,
              //         organization,
              //         keyid.toString,
              //         ResChangeCategory.APIKEY,
              //         public = false,
              //         ResChangeResource.APIKEY,
              //         ResChangeOperation.DELETED
              //       ).toResourceChangeRow
              //     } else {
              //       DBIO.successful(0)
              //     }
            } yield deleted).transactionally).map {
              case 0 =>
                (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("apikey.not.found")))
              case _ =>
                (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("apikey.deleted")))
            }.recover {
              case ex =>
                logger.error(s"Error deleting API key $keyid for $organization/$username", ex)
                (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("apikey.deletion.failed")))
            }
            
          case None =>
            Future.successful((StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "User not found")))
        }
    }
  }

  // === GET /v1/orgs/{organization}/users/{username}/apikeys/{keyid} ===
  @GET
  @Path("/{keyid}")
  @Operation(
  summary = "Get an API key by ID",
  description = "Returns API key with the given ID. Must be called by the user on their own behalf (if they are the owner) or by an organization admin.",
  parameters = Array(
    new Parameter(name = "organization", in = ParameterIn.PATH, required = true, description = "Organization ID"),
    new Parameter(name = "username", in = ParameterIn.PATH, required = true, description = "Username"),
    new Parameter(name = "keyid", in = ParameterIn.PATH, required = true, description = "API key ID")
  ),
  responses = Array(
    new responses.ApiResponse(
      responseCode = "200",
      description = "response body",
      content = Array(new Content(
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[ApiKeyMetadata]),
        examples = Array(
          new ExampleObject(
            value = """{
  "id": "string",
  "description": "string",
  "owner": "string",
  "lastUpdated": "string"
}"""
          )
        )
      ))
    ),
    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
    new responses.ApiResponse(responseCode = "403", description = "access denied"),
    new responses.ApiResponse(responseCode = "404", description = "not found")
  )
)
  def getUserApiKeyById(@Parameter(hidden = true) identity: Identity2,
                        @Parameter(hidden = true) organization: String,
                        @Parameter(hidden = true) username: String,
                        @Parameter(hidden = true) keyidStr: String,
                        @Parameter(hidden = true) resourceUuidOpt: Option[UUID]): Route = complete {
    Try(UUID.fromString(keyidStr)) match {
      case Failure(_) =>
        Future.successful((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, "Invalid UUID format for API key ID")))

      case Success(keyid) =>
        resourceUuidOpt match {
          case Some(userUuid) =>
            val keyQuery = Compiled {
              ApiKeysTQ.filter(k => k.id === keyid && k.user === userUuid && k.orgid === organization)
                      .filterIf(identity.isStandardUser)(k => k.user === identity.identifier.get)
                      .filterIf(identity.isOrgAdmin)(k => k.orgid === identity.organization)
                      .filterIf(identity.isHubAdmin)(k => UsersTQ.filter(u => u.user === k.user && ((u.isHubAdmin || u.isOrgAdmin) && !(u.organization === "root" && u.username === "root"))).exists)
                      .take(1)
            }

            db.run(keyQuery.result.headOption).map {
              case Some(keyRow) =>
                val ownerStr = s"$organization/$username"
                val metadata = new ApiKeyMetadata(keyRow, ownerStr)
                (StatusCodes.OK, metadata)

              case None =>
                (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("apikey.not.found")))
            }.recover {
              case ex =>
                logger.error(s"Failed to get API key $keyid for $organization/$username", ex)
                (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, "Failed to retrieve API key"))
            }

          case None =>
            Future.successful((StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "User not found")))
        }
    }
  }

  def userApiKeys(identity: Identity2): Route = {
    pathPrefix("orgs" / Segment / "users" / Segment / "apikeys") { (organization, username) =>
      val resourceType = "user"
      val resource = OrgAndId(organization, username).toString

      val cacheCallback: Future[(UUID, Boolean)] =
        cacheResourceOwnership.cachingF(organization, username, resourceType)(
          Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)
        ) {
          // logger.debug(s"[userApiKeys] UUID not found in cache, querying DB for $resource")
          getOwnerOfResource(organization, resource, resourceType)
        }

      def routeMethods(resourceIdentity: Option[UUID]): Route = {
        pathEnd {
          post {
            exchAuth(TUser(resource, resourceIdentity), Access.WRITE, validIdentity = identity) { _ =>
              postUserApiKey(identity, organization, username, resourceIdentity)
            }
          }
        } ~
        path(Segment) { keyidStr =>
          get {
            exchAuth(TUser(resource, resourceIdentity), Access.READ, validIdentity = identity) { _ =>
              getUserApiKeyById(identity, organization, username, keyidStr, resourceIdentity)
            }
          } ~
          delete {
            exchAuth(TUser(resource, resourceIdentity), Access.WRITE, validIdentity = identity) { _ =>
              deleteUserApiKey(identity, organization, username, keyidStr, resourceIdentity)
            }
          }
        }
      }

      onComplete(cacheCallback) {
        case Success((uuid, fromCache)) =>
          // logger.debug(s"[userApiKeys] Resolved UUID for $resource: $uuid (fromCache = $fromCache)")
          routeMethods(Some(uuid))
        case Failure(ex) =>
          // logger.warning(s"[userApiKeys] Failed to resolve UUID for $resource: ${ex.getMessage}")
          routeMethods(None)
      }
    }
  }
}