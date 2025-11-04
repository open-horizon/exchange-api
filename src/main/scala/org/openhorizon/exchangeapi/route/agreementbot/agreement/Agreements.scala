package org.openhorizon.exchangeapi.route.agreementbot.agreement

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.GetAgbotAgreementsResponse
import org.openhorizon.exchangeapi.table.agreementbot.agreement.{AgbotAgreement, AgbotAgreementsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/agreements")
trait Agreements extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/agreements ================================
  @DELETE
  @Operation(summary = "Deletes all Agreements this Agreement Bot (AgBot) has.",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
            responses =
              Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                    new responses.ApiResponse(responseCode = "403", description = "access denied"),
                    new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def deleteAgreements(@Parameter(hidden = true) agreementBot: String,
                       @Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route = {
    Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
    
    val INSTANT: Instant = Instant.now()
    
      complete({
        implicit val formats: Formats = DefaultFormats
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotAgreementsTQ.getAgreements(resource)
                                .delete
                                .asTry
                                .flatMap({
                                  case Success(v) =>
                                    if (0 < v) { // there were no db errors, but determine if it actually found it or not
                                      // Add the resource to the resourcechanges table
                                      Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Agreements Deleted: ${v}") }
                                      resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.DELETED, INSTANT).insert.asTry
                                    }
                                    else
                                      DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("no.agreements.found.for.agbot", resource))).asTry
                                  case Failure(t) =>
                                    DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes Logged:     ${v}") }
              (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.agreements.deleted")))
            case Failure(exception: DBProcessingError) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(exception.toComplete)}") }
              exception.toComplete
            case Failure(exception: org.postgresql.util.PSQLException) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("agbot.agreements.not.deleted", resource, exception.toString)))}") }
              ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("agbot.agreements.not.deleted", resource, exception.toString))
            case Failure(exception) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.agreements.not.deleted", resource, exception.toString))))}") }
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.agreements.not.deleted", resource, exception.toString)))
          })
      })
  }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/agreements ===================================
  @GET
  @Operation(summary = "Returns all Agreements this Agreement Bot (AgBot) is apart of.",
             description = "Can be run by the owning User or the Agbot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
  "agreements": {
    "agreementname": {
      "service": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "2019-05-14T16:34:37.173Z[UTC]",
      "dataLastReceived": ""
    }
  },
  "lastIndex": 0
}
""")),
                                                                   mediaType = "application/json",
                                                                   schema =
                                                                     new Schema(implementation = classOf[GetAgbotAgreementsResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def getAgreements(@Parameter(hidden = true) agreementBot: String,
                    @Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route = {
    Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
    
    complete {
      implicit val formats: Formats = DefaultFormats
      db.run(AgbotAgreementsTQ.getAgreements(resource).result)
        .map {
          list =>
            Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Agreements: ${list.size}") }
            val agreements: Map[String, AgbotAgreement] = list.map(e => e.agrId -> e.toAgbotAgreement).toMap
           
            if (agreements.nonEmpty)
              (StatusCodes.OK, GetAgbotAgreementsResponse(agreements))
            else {
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/agreements - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - agreements.nonEmpty: ${agreements.nonEmpty} - ${(StatusCodes.NotFound, Serialization.write(GetAgbotAgreementsResponse(Map.empty[String, AgbotAgreement])))}") }
              (StatusCodes.NotFound, GetAgbotAgreementsResponse(Map.empty[String, AgbotAgreement]))
            }
        }
    }
  }
  
  
  def agreements(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / "agreements") {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        val resource_type = "agreement_bot"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, agreementBot, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
              ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
            }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          get {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.READ, validIdentity = identity) {
              _ =>
                getAgreements(agreementBot, identity, organization, resource)
            }
          } ~
          delete {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteAgreements(agreementBot, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
