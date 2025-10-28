package org.openhorizon.exchangeapi.route.agreementbot.deploymentpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.{GetAgbotBusinessPolsResponse, PostAgbotBusinessPolRequest}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.{AgbotBusinessPol, AgbotBusinessPolRow, AgbotBusinessPols, AgbotBusinessPolsTQ}
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._
import slick.lifted.CompiledStreamingExecutable

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/agbots/{agreementbot}/deployment/policies")
trait DeploymentPolicies extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // ========== DELETE /orgs/{organization}/agbots/{agreementbot}/deployment/policies ==============================
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
  def deleteDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                               @Parameter(hidden = true) identity: Identity2,
                               @Parameter(hidden = true) organization: String,
                               @Parameter(hidden = true) resource: String): Route =
    delete {
      Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
      
      val INSTANT: Instant = Instant.now()
      
      complete({
        implicit val formats: Formats = DefaultFormats
        // remove does *not* throw an exception if the key does not exist
        db.run(AgbotBusinessPolsTQ.getBusinessPols(resource)
                                  .delete
                                  .asTry
                                  .flatMap({
                                    case Success(v) =>
                                      if (v > 0) {// there were no db errors, but determine if it actually found it or not
                                        // Add the resource to the resourcechanges table
                                        Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Managed Deployment Policies Deleted: ${v}") }
                                        resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, public = false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED, INSTANT).insert.asTry
                                      }
                                      else
                                        DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("buspols.not.found", resource))).asTry
                                    case Failure(t) =>
                                      DBIO.failed(t).asTry}))
          .map({
            case Success(v) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes Logged:                      ${v}") }
              (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspols.deleted")))
            case Failure(exception: DBProcessingError) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${write(exception.toComplete)}") }
              exception.toComplete
            case Failure(exception: org.postgresql.util.PSQLException) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspols.not.deleted", resource, exception.toString)))}") }
              ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspols.not.deleted", resource, exception.toString))
            case Failure(exception) =>
              Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspols.not.deleted", resource, exception.toString))))}") }
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspols.not.deleted", resource, exception.toString)))
          })
      })
    }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/deployment/policies =================================
  @GET
  @Operation(description = "Can be run by the owning User or the AgBot.",
             summary = "Returns all Deployment Policies served by this Agreement Bot (AgBot)",
             parameters =
               Array(// Path parameters
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Organization identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "organization",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String])),
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Agreement Bot identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "agreementbot",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String])),
                     // Query parameters
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by a Deployment Policy",
                                   in              = ParameterIn.QUERY,
                                   name            = "deployment_policy",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]])),
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by the Organization of a Deployment Policy",
                                   in              = ParameterIn.QUERY,
                                   name            = "deployment_policy_organization",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]])),
                     new Parameter(allowEmptyValue = true,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Filter by the Organization of a Node",
                                   in              = ParameterIn.QUERY,
                                   name            = "node_organization",
                                   required        = false,
                                   schema          = new Schema(implementation = classOf[Option[String]]))
               ),
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(examples =
                                                   Array(new ExampleObject(value = """{
"businessPols" : {
  "buspolid": {
    "businessPolOrgid": "string",
    "businessPol": "string",
    "deployment_policy": "string",
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
  def getDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                            @Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String,
                            @Parameter(hidden = true) resource: String): Route = {
    parameter("deployment_policy".?,
              "deployment_policy_organization".?,
              "node_organization".?) {
      (deploymentPolicy,
       deploymentPolicyOrganization,
       nodeOrganization) =>
        Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/policies?deployment_policy=${deploymentPolicy.getOrElse("None")}&deployment_policy_organization=${deploymentPolicyOrganization.getOrElse("None")}&node_organization=${nodeOrganization.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        
        val getAgbotManagedDeploymentPolcies: CompiledStreamingExecutable[Query[(AgbotBusinessPols, Rep[String]), (AgbotBusinessPolRow, String), Seq], Seq[(AgbotBusinessPolRow, String)], (AgbotBusinessPolRow, String)] =
          for {
            managedDeploymentPolicies <-
              Compiled(AgbotsTQ.filter(agbot => agbot.id === resource &&
                                       agbot.orgid === organization)
                               .filterIf(identity.isStandardUser)(agbot => agbot.owner === identity.identifier.get || agbot.orgid === "IBM")
                               .filterIf((identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isHubAdmin || identity.isNode || identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                               .map(_.id)
                               .join(AgbotBusinessPolsTQ.filterOpt(deploymentPolicy)((policy, deploymentPolicy) => (if (deploymentPolicy.contains("%")) policy.businessPol.like(deploymentPolicy) else policy.businessPol === deploymentPolicy))
                                                        .filterOpt(deploymentPolicyOrganization)((policy, deploymentPolicyOrganization) => (if (deploymentPolicyOrganization.contains("%")) policy.businessPolOrgid.like(deploymentPolicyOrganization) else policy.businessPolOrgid === deploymentPolicyOrganization))
                                                        .filterOpt(nodeOrganization)((policy, nodeOrganization) => (if (nodeOrganization.contains("%")) policy.nodeOrgid.like(nodeOrganization) else policy.nodeOrgid === nodeOrganization)))
                               .on(_ === _.agbotId)
                               .sortBy(policy => (policy._2.businessPolOrgid.asc.nullsDefault, policy._2.businessPol.asc.nullsDefault))
                               .map(policy => (policy._2, policy._2.busPolId)))
          } yield (managedDeploymentPolicies)
          
        complete {
          implicit val formats: Formats = DefaultFormats
          db.run(getAgbotManagedDeploymentPolcies.result.transactionally.asTry).map {
            case Success(result) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/policies?deployment_policy=${deploymentPolicy.getOrElse("None")}&deployment_policy_organization=${deploymentPolicyOrganization.getOrElse("None")}&node_organization=${nodeOrganization.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result size: ${result.size}") }
              
              if(result.nonEmpty)
                (StatusCodes.OK, GetAgbotBusinessPolsResponse(businessPols = (result.map(policies => policies._2 -> new AgbotBusinessPol(policies._1)).toMap)))
              else {
                Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/policies?deployment_policy=${deploymentPolicy.getOrElse("None")}&deployment_policy_organization=${deploymentPolicyOrganization.getOrElse("None")}&node_organization=${nodeOrganization.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result.nonEmpty:${result.nonEmpty} - ${(StatusCodes.NotFound, Serialization.write(GetAgbotBusinessPolsResponse(businessPols = Map.empty[String, AgbotBusinessPol])))}") }
                (StatusCodes.NotFound, GetAgbotBusinessPolsResponse(businessPols = Map.empty[String, AgbotBusinessPol]))
              }
            case Failure(exception: org.postgresql.util.PSQLException) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/policies?deployment_policy=${deploymentPolicy.getOrElse("None")}&deployment_policy_organization=${deploymentPolicyOrganization.getOrElse("None")}&node_organization=${nodeOrganization.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage)))}") }
              ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage))
            case Failure(exception) =>
              Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/deployment/policies?deployment_policy=${deploymentPolicy.getOrElse("None")}&deployment_policy_organization=${deploymentPolicyOrganization.getOrElse("None")}&node_organization=${nodeOrganization.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error"))))}") }
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
          }
        }
    }
  }
  
  // ========== POST /orgs/{organization}/agbots/{agreementbot}/deployment/policies ================================
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
  def postDeploymentPolicies(@Parameter(hidden = true) agreementBot: String,
                             @Parameter(hidden = true) identity: Identity2,
                             @Parameter(hidden = true) organization:String,
                             @Parameter(hidden = true) resource: String): Route =
    post {
      entity(as[PostAgbotBusinessPolRequest]) {
        reqBody =>
          Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
          Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Request { businessPol:${reqBody.businessPol}, businessPolOrgid:${reqBody.businessPolOrgid}, nodeOrgid:${reqBody.nodeOrgid.getOrElse("None")} }") }
            
          val INSTANT: Instant = Instant.now()
          
          val createManagedDeploymentPolicy =
            for {
              authorized <-
                if (identity.isNode ||
                    (((identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isOrgAdmin || identity.isStandardUser) &&
                     (reqBody.businessPolOrgid != identity.organization ||
                      reqBody.businessPolOrgid != reqBody.nodeOrgid.getOrElse(reqBody.businessPolOrgid))))
                  DBIO.failed(new IllegalAccessException())
                else
                  Compiled(AgbotsTQ.filter(agbot => agbot.id === resource &&
                                                    agbot.orgid === organization)
                                   // Public management of Deployment Policies.
                                   // TODO: Think on authorization
                                   .filterIf(identity.isStandardUser)(agbot => agbot.owner === identity.identifier.get || agbot.orgid === "IBM")
                                   .filterIf(identity.isAgbot)(agbot => agbot.id === identity.resource &&
                                                                        agbot.orgid === identity.organization)
                                   // Public management of Deployment Policies.
                                   // TODO: Think on authorization
                                   .filterIf(identity.isHubAdmin || identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                                   .map(_.id)
                                   .take(1)
                                   .length)
                    .result
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Authorized:         ${authorized == 1}") }
              
              _ <-
                if (authorized == 1)
                  DBIO.successful(())
                else
                  DBIO.failed(new IllegalAccessException())
              
              numManagedDeploymentPatternsCreated <-
                AgbotBusinessPolsTQ +=
                  AgbotBusinessPolRow(agbotId          = resource,
                                      businessPol      = reqBody.businessPol,
                                      businessPolOrgid = reqBody.businessPolOrgid,
                                      lastUpdated      = INSTANT.toString,
                                      nodeOrgid        = reqBody.nodeOrgid.getOrElse(reqBody.businessPol),
                                      busPolId         = s"${reqBody.businessPolOrgid}_${reqBody.businessPol}_${reqBody.nodeOrgid.getOrElse(reqBody.businessPolOrgid)}")
              
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Managed Deployment Patterns Created: ${numManagedDeploymentPatternsCreated}") }
              
              numChangeRecordsCreated <-
                ResourceChangesTQ +=
                  ResourceChangeRow(category    = ResChangeCategory.AGBOT.toString,
                                    changeId    = 0L,
                                    id          = agreementBot,
                                    lastUpdated = INSTANT,
                                    operation   = ResChangeOperation.CREATED.toString,
                                    orgId       = organization,
                                    public      = "false",
                                    resource    = ResChangeResource.AGBOTBUSINESSPOLS.toString)
              
              _ =
                Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes Logged:                      ${numChangeRecordsCreated}") }
              
            } yield ()
            
          complete {
            implicit val formats: Formats = DefaultFormats
            val managedPolicyIdentifier: String = s"${reqBody.businessPolOrgid}_${reqBody.businessPol}_${reqBody.nodeOrgid.getOrElse(reqBody.businessPolOrgid)}"
            
            db.run(createManagedDeploymentPolicy.transactionally.asTry)
              .map {
                case Success(_) =>
                  (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.added", managedPolicyIdentifier)))
                case Failure(exception: IllegalAccessException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, Serialization.write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.not.inserted", managedPolicyIdentifier, resource, exception.getMessage))))}") }
                  (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage match { case errorMessage: String => errorMessage; case _ => ""})))
                case Failure(exception: NullPointerException) =>
                  Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadRequest, Serialization.write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.in.exchange", managedPolicyIdentifier, resource, exception.getMessage))))}") }
                  (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.in.exchange", managedPolicyIdentifier, resource, exception.getMessage)))
                case Failure(exception: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isDuplicateKeyError(exception)) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Conflict, write(ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.foragbot.already.exists", managedPolicyIdentifier, resource))))}") }
                    (StatusCodes.Conflict, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.foragbot.already.exists", managedPolicyIdentifier, resource)))
                  }
                  else if (ExchangePosgtresErrorHandling.isAccessDeniedError(exception)) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage)))
                  }
                  else {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getServerErrorMessage)))}") }
                    ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getServerErrorMessage))
                  }
                case Failure(exception) =>
                  if (exception.getMessage.startsWith("Access Denied:")) {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.Forbidden, write(ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage)))
                  }
                  else {
                    Future { logger.debug(s"POST /orgs/${organization}/agbots/${agreementBot}/deployment/policies - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.BadRequest, write(ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage))))}") }
                    (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.inserted", managedPolicyIdentifier, resource, exception.getMessage)))
                  }
              }
          }
      }
    }
  
  def deploymentPoliciesAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / ("businesspols" | "deployment" ~ Slash ~ "policy")) {
      (organization,
       agreementBot) =>
        val resource: String = OrgAndId(organization, agreementBot).toString
        val resource_type = "agreement_bot"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, agreementBot,resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          get {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.READ, validIdentity = identity) {
              _ =>
                getDeploymentPolicies(agreementBot, identity, organization, resource)
            }
          } ~
          (delete | post) {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteDeploymentPolicies(agreementBot, identity, organization, resource) ~
                postDeploymentPolicies(agreementBot, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
