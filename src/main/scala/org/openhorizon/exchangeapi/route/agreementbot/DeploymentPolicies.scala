package org.openhorizon.exchangeapi.route.agreementbot

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.{AgbotBusinessPol, AgbotBusinessPolsTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import org.openhorizon.exchangeapi.table
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/businesspols")
trait DeploymentPolicies extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/businesspols ==============================
  @DELETE
  @Operation(summary = "Deletes all Deployment Policies this Agreement Bot (AgBot) is serving.",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  private def deleteDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                                       @Parameter(hidden = true) identity: Identity2,
                                       @Parameter(hidden = true) organization: String,
                                       @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotBusinessPolsTQ.getBusinessPols(resource)
                                  .delete
                                  .asTry
                                  .flatMap({
                                    case Success(v) =>
                                      if (v > 0) {// there were no db errors, but determine if it actually found it or not
                                        // Add the resource to the resourcechanges table
                                        logger.debug("DELETE /agbots/" + agreementBot + "/businesspols result: " + v)
                                        resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED, INSTANT).insert.asTry
                                      }
                                      else
                                        DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("buspols.not.found", resource))).asTry
                                    case Failure(t) =>
                                      DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              logger.debug("DELETE /agbots/" + agreementBot + "/businesspols updated in changes table: " + v)
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspols.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspols.not.deleted", resource, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspols.not.deleted", resource, t.toString)))
          })
      })
    }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/businesspols =================================
  @GET
  @Operation(summary = "Returns all Deployment Policies served by this Agreement Bot (AgBot)",
             description = "Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization identifier"),
                     new Parameter(name = "agreementbot", in = ParameterIn.PATH, description = "Agreement Bot identifier")),
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
                                                                  schema =
                                                                    new Schema(implementation = classOf[GetAgbotBusinessPolsResponse])))),
                     new responses.ApiResponse(responseCode = "400", description = "bad input"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  private def getDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                                    @Parameter(hidden = true) identity: Identity2,
                                    @Parameter(hidden = true) organization: String,
                                    @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      db.run(AgbotBusinessPolsTQ.getBusinessPols(resource).result)
        .map({
          list =>
            logger.debug(s"GET /orgs/$organization/agbots/$agreementBot/businesspols - result size: ${list.size}")
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
  
  // ========== POST /orgs/{organization}/agbots/{agreementbot}/businesspols ================================
  @POST
  @Operation(summary = "Adds a Deployment Policy that this Agreement Bot (AgBot) should serve",
             description = "This AgBot should find Nodes for this Deployment Policy to make agreements with. This is called by the owning User or the AgBot to give their information about the Deployment Policy.",
             parameters =
               Array(new Parameter(name = "organization",
                                   in = ParameterIn.PATH,
                                   description = "Organization identifier"),
                     new Parameter(name = "agreementbot",
                                   in = ParameterIn.PATH,
                                   description = "Agreement Bot identifier")),
             requestBody =
               new RequestBody(content =
                 Array(new Content(examples =
                   Array(new ExampleObject(value ="""{
  "businessPolOrgid": "string",
  "businessPol": "string",
  "nodeOrgid": "string"
}
""")),
                                   mediaType = "application/json",
                                   schema = new Schema(implementation = classOf[PostAgbotBusinessPolRequest]))),
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  private def postDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                                     @Parameter(hidden = true) identity: Identity2,
                                     @Parameter(hidden = true) organization:String,
                                     @Parameter(hidden = true) resource: String): Route =
    post {
      entity(as[PostAgbotBusinessPolRequest]) {
        reqBody =>
          logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/businesspols - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          validateWithMsg(reqBody.getAnyProblem) {
            
            val INSTANT: Instant = Instant.now()
            
            complete({
              val deploymentPolicy: String = reqBody.formId
              
              db.run(BusinessPoliciesTQ.getBusinessPolicy(OrgAndId(reqBody.businessPolOrgid, reqBody.businessPol).toString)
                                       .length
                                       .result
                                       .asTry
                                       .flatMap({
                                         case Success(num) =>
                                           logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/businesspols business policy validation: " + num)
                                           if (0 < num ||
                                               reqBody.businessPol == "*")
                                             reqBody.toAgbotBusinessPolRow(resource, deploymentPolicy).insert.asTry
                                           else
                                             DBIO.failed(new Throwable(ExchMsg.translate("buspol.not.in.exchange"))).asTry
                                        case Failure(t) =>
                                          DBIO.failed(t).asTry})
                                       .flatMap({
                                         case Success(v) => // Add the resource to the resourcechanges table
                                           logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/businesspols result: " + v)
                                           resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.CREATED, INSTANT).insert.asTry
                                         case Failure(t) =>
                                           DBIO.failed(t).asTry}))
                .map({
                  case Success(v) =>
                    logger.debug("POST /orgs/" + organization + "/agbots/" + agreementBot + "/businesspols updated in changes table: " + v)
                    (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.added", deploymentPolicy)))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t))
                      (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.foragbot.already.exists", deploymentPolicy, resource)))
                    else if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                      (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", deploymentPolicy, resource, t.getMessage)))
                    else
                      ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.inserted", deploymentPolicy, resource, t.getServerErrorMessage))
                  case Failure(t) =>
                    if (t.getMessage.startsWith("Access Denied:"))
                      (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", deploymentPolicy, resource, t.getMessage)))
                    else
                      (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.inserted", deploymentPolicy, resource, t.getMessage)))
                })
            })
          }
      }
    }
  
  def deploymentPoliciesAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / "businesspols") {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        
        get {
          exchAuth(TAgbot(resource), Access.READ, validIdentity = identity) {
            _ =>
              getDeploymentPolicies(agreementBot, identity, organization, resource)
          }
        } ~
        (delete | post) {
          exchAuth(TAgbot(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteDeploymentPolicies(agreementBot, identity, organization, resource) ~
              postDeploymentPolicies(agreementBot, identity, organization, resource)
          }
        }
    }
}
