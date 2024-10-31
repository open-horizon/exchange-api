package org.openhorizon.exchangeapi.route.deploymentpattern.key

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, delete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp.exchAuth
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TPattern}
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.key.PatternKeysTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/patterns/{pattern}/keys")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
trait Keys extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{organization}/patterns/{pattern}/keys ===============================
  @DELETE
  @Path("{pattern}/keys")
  @Operation(summary = "Deletes all keys of a pattern", description = "Deletes all of the current keys/certs for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def deleteKeysDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                                  @Parameter(hidden = true) organization: String,
                                  @Parameter(hidden = true) resource: String): Route =
    {
      complete({
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(resource).result.asTry.flatMap({
          case Success(public) =>
            // Get the public field before doing delete
            logger.debug("DELETE /patterns/" + deploymentPattern + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternKeysTQ.getKeys(resource).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /patterns/" + deploymentPattern + "/keys result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              ResourceChange(0L, organization, deploymentPattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.pattern.keys.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /patterns/" + deploymentPattern + "/keys result: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.keys.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.keys.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.keys.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/patterns/{pattern}/keys ================================ */
  @GET
  @Path("{pattern}/keys")
  @Operation(summary = "Returns all keys/certs for this pattern", description = "Returns all the signing public keys/certs for this pattern. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[List[String]])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def getKeysDeploymentPattern(@Parameter(hidden = true) deploymentPattern: String,
                               @Parameter(hidden = true) organization: String,
                               @Parameter(hidden = true) resource: String): Route =
    {
      complete({
        db.run(PatternKeysTQ.getKeys(resource).result).map({ list =>
          logger.debug(s"GET /orgs/$organization/patterns/$deploymentPattern/keys result size: ${list.size}")
          val code: StatusCode = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.map(_.keyId))
        })
      })
    }
  
  val keysDeploymentPattern: Route =
    path("orgs" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns") / Segment / "keys") {
      (organization, deploymentPattern) =>
        val resource: String = OrgAndId(organization, deploymentPattern).toString
        
        delete {
          exchAuth(TPattern(resource), Access.WRITE) {
            _ =>
              deleteKeysDeploymentPattern(deploymentPattern, organization, resource)
          }
        } ~
        get {
          exchAuth(TPattern(resource),Access.READ) {
            _ =>
              getKeysDeploymentPattern(deploymentPattern, organization, resource)
          }
        }
    }
}
