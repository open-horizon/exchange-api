package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{PathMatchers, Route}
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, CompositeId, DBProcessingError, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.agreement.{AgbotAgreement, AgbotAgreementsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.table
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/agreements/{agreement}")
trait Agreement extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/agreements/{agreement} ====================
  @DELETE
  @Operation(summary = "Deletes an Agreement of an Agreement Bot (AgBot)",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "agreement", in = ParameterIn.PATH, description = "Agreement identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def deleteAgreement(@Parameter(hidden = true) agreement: String,
                      @Parameter(hidden = true) agreementBot: String,
                      @Parameter(hidden = true) organization: String,
                      @Parameter(hidden = true) resource: String): Route =
    delete {
      complete({
        db.run(AgbotAgreementsTQ.getAgreement(resource, agreement)
                                .delete.asTry
                                .flatMap({
                                  case Success(v) =>
                                    // Add the resource to the resourcechanges table
                                    logger.debug("DELETE /agbots/" + agreementBot + "/agreements/" + agreement + " result: " + v)
                                    if (0 < v) // there were no db errors, but determine if it actually found it or not
                                      resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
                                    else
                                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("agreement.for.agbot.not.found", agreement, resource))).asTry
                                  case Failure(t) =>
                                    DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              logger.debug("DELETE /agbots/" + agreementBot + "/agreements/" + agreement + " updated in changes table: " + v)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agbot.agreement.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agreement.for.agbot.not.deleted", agreement, resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agreement.for.agbot.not.deleted", agreement, resource, t.toString)))
          })
      })
    }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/agreements/{agreement} =======================
  @GET
  @Operation(summary = "Returns an Agreement for an Agreement Bot (AgBot)",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "agreement", in = ParameterIn.PATH, description = "Agreement identifier")),
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
  def getAgreement(@Parameter(hidden = true) agreement: String,
                   @Parameter(hidden = true) agreementBot: String,
                   @Parameter(hidden = true) organization: String,
                   @Parameter(hidden = true) resource: String): Route =
    complete({
      db.run(AgbotAgreementsTQ.getAgreement(resource, agreement).result)
        .map({
          list =>
            logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/agreements/$agreement result size: ${list.size}")
            val agreements: Map[String, AgbotAgreement] = list.map(e => e.agrId -> e.toAgbotAgreement).toMap
            val code: StatusCode =
              if (agreements.nonEmpty)
                StatusCodes.OK
              else
                StatusCodes.NotFound
            (code, GetAgbotAgreementsResponse(agreements, 0))
        })
    })
  
  
  // ========== PUT /orgs/{organization}/agbots/{agreementbot}/agreements/{agreement} =======================
  @PUT
  @Operation(summary = "Adds/updates an Agreement of an Agreement Bot (AgBot)",
             description = "This is called by the owning User or the AgBot to give their information about the Agreement.",
             parameters =
               Array(new Parameter(name = "organization",
                                   in = ParameterIn.PATH,
                                   description = "Organization identifier."),
                     new Parameter(name = "agreementbot",
                                   in = ParameterIn.PATH,
                                   description = "Agreement Bot identifier"),
                     new Parameter(name = "agreement",
                                   in = ParameterIn.PATH,
                                   description = "Agreement identifier")),
             requestBody =
               new RequestBody(content =
                 Array(new Content(examples =
                   Array(new ExampleObject(value = """{
  "service": {
    "orgid": "string",
    "pattern": "string",
    "url": "string"
  },
  "state": "string"
}
""")),
                                   mediaType = "application/json",
                                   schema = new Schema(implementation = classOf[PutAgbotAgreementRequest]))),
                               required = true),
             responses =
               Array(new responses.ApiResponse(responseCode = "201",
                                               description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema =
                                                                     new Schema(implementation = classOf[ApiResponse])))),
                     new responses.ApiResponse(responseCode = "401",
                                               description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403",
                                               description = "access denied"),
                     new responses.ApiResponse(responseCode = "404",
                                               description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/agreement")
  def putAgreement(@Parameter(hidden = true) agreement: String,
                   @Parameter(hidden = true) agreementBot: String,
                   @Parameter(hidden = true) organization: String,
                   @Parameter(hidden = true) resource: String):Route =
    put {
      entity(as[PutAgbotAgreementRequest]) {
        reqBody =>
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val maxAgreements: Int = Configuration.getConfig.getInt("api.limits.maxAgreements")
              val getNumOwnedDbio =
                if (maxAgreements == 0)
                  DBIO.successful(0)
                else
                  AgbotAgreementsTQ.getNumOwned(resource).result // avoid DB read for this if there is no max
              db.run(getNumOwnedDbio.flatMap({
                                      xs =>
                                        if (maxAgreements != 0)
                                          logger.debug("PUT /orgs/" + organization + "/agbots/" + agreementBot + "/agreements/" + agreement + " num owned: " + xs)
                                          
                                        val numOwned: Int = xs // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
                                        if (maxAgreements == 0 ||
                                            numOwned <= maxAgreements)
                                          reqBody.toAgbotAgreementRow(resource, agreement).upsert.asTry
                                        else
                                          DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.agreements", maxAgreements))).asTry})
                                    .flatMap({
                                      case Success(v) => // Add the resource to the resourcechanges table
                                        logger.debug("PUT /orgs/" + organization + "/agbots/" + agreementBot + "/agreements/" + agreement + " result: " + v)
                                        resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTAGREEMENTS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                                      case Failure(t) =>
                                        DBIO.failed(t).asTry}))
                .map({
                  case Success(v) =>
                    logger.debug("PUT /orgs/" + organization + "/agbots/" + agreementBot + "/agreements/" + agreement + " updated in changes table: " + v)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agreement.added.or.updated")))
                  case Failure(t: DBProcessingError) =>
                    t.toComplete
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agreement.not.inserted.or.updated", agreement, resource, t.toString))
                  case Failure(t) =>
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agreement.not.inserted.or.updated", agreement, resource, t.toString)))
                })
            })
          }
      }
    }
  
  
  def agreement: Route =
    path("orgs" / Segment / "agbots" / Segment / "agreements" / Segment) {
      (organization,
       agreementBot,
       agreement) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            _ =>
              getAgreement(agreement, agreementBot, organization, resource)
          }
        } ~
        (delete | put) {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              deleteAgreement(agreement, agreementBot, organization, resource) ~
              putAgreement(agreement, agreementBot, organization, resource)
          }
        }
    }
}
