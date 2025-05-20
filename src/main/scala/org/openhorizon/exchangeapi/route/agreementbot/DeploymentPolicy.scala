package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.{AgbotBusinessPol, AgbotBusinessPolsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.table
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
  private def deleteDeploymentPolicy(@Parameter(hidden = true) agreementBot: String,
                                     @Parameter(hidden = true) deploymentPolicy: String,
                                     @Parameter(hidden = true) identity: Identity2,
                                     @Parameter(hidden = true) organization: String,
                                     @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - By ${identity.resource}:${identity.role}")
    complete({
      db.run(AgbotBusinessPolsTQ.getBusinessPol(resource, deploymentPolicy)
                                .delete
                                .asTry
                                .flatMap({
                                  case Success(v) =>
                                    // Add the resource to the resourcechanges table
                                    logger.debug("DELETE /agbots/" + agreementBot + "/businesspols/" + deploymentPolicy + " result: " + v)
                                    if (v > 0) // there were no db errors, but determine if it actually found it or not
                                      resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED).insert.asTry
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
  private def getDeploymentPolicy(@Parameter(hidden = true) agreementBot: String,
                                  @Parameter(hidden = true) deploymentPolicy: String,
                                  @Parameter(hidden = true) identity: Identity2,
                                  @Parameter(hidden = true) organization: String,
                                  @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - By ${identity.resource}:${identity.role}")
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
  
  def deploymentPolicyAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / "businesspols" / Segment) {
      (organization,
       agreementBot,
       deploymentPolicy) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ, validIdentity = identity) {
            _ =>
              getDeploymentPolicy(agreementBot, deploymentPolicy, identity, organization, resource)
          }
        } ~
        delete {
          exchAuth(TAgbot(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteDeploymentPolicy(agreementBot, deploymentPolicy, identity, organization, resource)
          }
        }
    }
}
