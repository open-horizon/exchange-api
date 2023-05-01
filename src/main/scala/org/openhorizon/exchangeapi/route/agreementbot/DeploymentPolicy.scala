package org.openhorizon.exchangeapi.route.agreementbot

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.{complete, path, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.openhorizon.exchangeapi.auth.DBProcessingError
import org.openhorizon.exchangeapi.table.{AgbotBusinessPol, AgbotBusinessPolsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, AuthenticationSupport, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, OrgAndId, TAgbot}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/businesspols/{deploymentpolicy}")
trait DeploymentPolicy extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/businesspols/{deploymentpolicy} ===========
  @DELETE
  @Operation(summary = "Deletes a Deployment Policy this Agreement Bot (AgBot) is serving",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "deploymentpolicy", in = ParameterIn.PATH, description = "Deployment Policy identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  private def deleteDeploymentPolicy(agreementBot: String,
                                     deploymentPolicy: String,
                                     organization: String,
                                     resource: String): Route = {
    complete({
      db.run(AgbotBusinessPolsTQ.getBusinessPol(resource, deploymentPolicy)
                                .delete
                                .asTry
                                .flatMap({
                                  case Success(v) =>
                                    // Add the resource to the resourcechanges table
                                    logger.debug("DELETE /agbots/" + agreementBot + "/businesspols/" + deploymentPolicy + " result: " + v)
                                    if (v > 0) // there were no db errors, but determine if it actually found it or not
                                      ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED).insert.asTry
                                    else
                                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("buspol.not.found", deploymentPolicy, resource))).asTry
                                  case Failure(t) =>
                                    DBIO.failed(t).asTry}))
        .map({
          case Success(v) =>
            logger.debug("DELETE /agbots/" + agreementBot + "/businesspols/" + deploymentPolicy + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, t.toString)))
        })
    })
  }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/businesspols/{deploymentpolicy} ==============
  @GET
  @Operation(summary = "Returns a Deployment Policy this Agreement Bot (AgBot) is serving",
             description = "The buspolid should be in the form businessPolOrgid_businessPol. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier."),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier"),
                     new Parameter(name = "deploymentpolicy", in = ParameterIn.PATH, description = "Deployment Policy identifier")),
            responses =
              Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                              content =
                                                Array(new Content(examples =
                                                  Array(new ExampleObject(value = """{
"businessPols" : {
  "buspolid": {
    "businessPolOrgid": "string",
    "businessPol": "string",
    "nodeOrgid" : "string",
    "lastUpdated": "string"
  }
}
}""")),
                                                                  mediaType = "application/json",
                                                                  schema = new Schema(implementation = classOf[GetAgbotBusinessPolsResponse])))),
                    new responses.ApiResponse(responseCode = "400", description = "bad input"),
                    new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                    new responses.ApiResponse(responseCode = "403", description = "access denied"),
                    new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  private def getDeploymentPolicy(agreementBot: String,
                                  deploymentPolicy: String,
                                  organization: String,
                                  resource: String): Route = {
    complete({
      db.run(AgbotBusinessPolsTQ.getBusinessPol(resource, deploymentPolicy).result)
        .map({
          list =>
            logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/businesspols/$deploymentPolicy result size: ${list.size}")
            val businessPols: Map[String, AgbotBusinessPol] = list.map(e => e.busPolId -> e.toAgbotBusinessPol).toMap
            val code: StatusCode =
              if (businessPols.nonEmpty)
                StatusCodes.OK
              else
                StatusCodes.NotFound
            (code, GetAgbotBusinessPolsResponse(businessPols))
        })
    })
  }
  
  val deploymentPolicyAgreementBot: Route =
    path("orgs" / Segment / "agbots" / Segment / "businesspols" / Segment) {
      (organization,
       agreementBot,
       deploymentPolicy) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ) {
            _ =>
              getDeploymentPolicy(agreementBot, deploymentPolicy, organization, resource)
          }
        } ~
        delete {
          exchAuth(TAgbot(resource), Access.WRITE) {
            _ =>
              deleteDeploymentPolicy(agreementBot, deploymentPolicy, organization, resource)
          }
        }
    }
}
