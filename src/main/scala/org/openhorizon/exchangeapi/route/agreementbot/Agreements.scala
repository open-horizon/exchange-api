package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.agreement.{AgbotAgreement, AgbotAgreementsTQ}
import org.openhorizon.exchangeapi.table.{organization, resourcechange}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
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
  def deleteAgreements(id: String,
                       orgid: String,
                       compositeId: String): Route = {
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotAgreementsTQ.getAgreements(compositeId)
                                .delete
                                .asTry
                                .flatMap({
                                  case Success(v) =>
                                    if (0 < v) { // there were no db errors, but determine if it actually found it or not
                                      // Add the resource to the resourcechanges table
                                      logger.debug("DELETE /agbots/" + id + "/agreements result: " + v)
                                      resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
                                    }
                                    else
                                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.agreements.found.for.agbot", compositeId))).asTry
                                  case Failure(t) =>
                                    DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              logger.debug("DELETE /agbots/" + id + "/agreements updated in changes table: " + v)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.agreements.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agbot.agreements.not.deleted", compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agbot.agreements.not.deleted", compositeId, t.toString)))
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
  def getAgreements(agreementBot: String,
                    organization: String,
                    resource: String): Route = {
    complete({
      db.run(AgbotAgreementsTQ.getAgreements(resource).result)
        .map({
          list =>
            logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/agreements result size: ${list.size}")
            val agreements: Map[String, AgbotAgreement] = list.map(e => e.agrId -> e.toAgbotAgreement).toMap
            val code: StatusCode =
              if (agreements.nonEmpty)
                StatusCodes.OK
              else
                StatusCodes.NotFound
            (code, GetAgbotAgreementsResponse(agreements, 0))
        })
    })
  }
  
  
  val agreements: Route =
    path("orgs" / Segment / "agbots" / Segment / "agreements") {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            _ =>
              getAgreements(agreementBot, organization, resource)
          }
        } ~
        delete {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              deleteAgreements(agreementBot, organization, resource)
          }
        }
    }
}
