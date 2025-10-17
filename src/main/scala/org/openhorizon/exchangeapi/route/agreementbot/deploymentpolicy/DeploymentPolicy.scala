package org.openhorizon.exchangeapi.route.agreementbot.deploymentpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TAgbot}
import org.openhorizon.exchangeapi.route.agreementbot.GetAgbotBusinessPolsResponse
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.{AgbotBusinessPol, AgbotBusinessPolRow, AgbotBusinessPols, AgbotBusinessPolsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import scalacache.modes.scalaFuture.mode
import slick.jdbc.PostgresProfile.api._
import slick.lifted.CompiledStreamingExecutable

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
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
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Deployment Policy identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "deploymentpolicy",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String]))),
             responses =
               Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied"),
                     new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "agreement bot/deployment policy")
  def deleteDeploymentPolicy(@Parameter(hidden = true) agreementBot: String,
                                     @Parameter(hidden = true) deploymentPolicy: String,
                                     @Parameter(hidden = true) identity: Identity2,
                                     @Parameter(hidden = true) organization: String,
                                     @Parameter(hidden = true) resource: String): Route = {
    Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
    
    val INSTANT: Instant = Instant.now()
    
    complete({
      implicit val formats: Formats = DefaultFormats
      db.run(AgbotBusinessPolsTQ.getBusinessPol(resource, deploymentPolicy)
                                .delete
                                .asTry
                                .flatMap({
                                  case Success(v) =>
                                    // Add the resource to the resourcechanges table
                                    Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Managed Deployment Policy Deleted: ${v}") }
                                    if (v > 0) // there were no db errors, but determine if it actually found it or not
                                      resourcechange.ResourceChange(0L, organization, agreementBot, ResChangeCategory.AGBOT, false, ResChangeResource.AGBOTBUSINESSPOLS, ResChangeOperation.DELETED, INSTANT).insert.asTry
                                    else
                                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("buspol.not.found", deploymentPolicy, resource))).asTry
                                  case Failure(t) =>
                                    DBIO.failed(t).asTry}))
        .map({
          case Success(v) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changes Logged:                    ${v}") }
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.deleted")))
          case Failure(exception: DBProcessingError) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(exception.toComplete)}") }
            exception.toComplete
          case Failure(exception: org.postgresql.util.PSQLException) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, exception.toString)))}") }
            ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, exception.toString))
          case Failure(exception) =>
            Future { logger.debug(s"DELETE /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, Serialization.write(ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, exception.toString))))}") }
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("buspol.not.deleted", deploymentPolicy, resource, exception.toString)))
        })
    })
  }
  
  // ========== GET /orgs/{organization}/agbots/{agreementbot}/businesspols/{deploymentpolicy} ==============
  @GET
  @Operation(summary = "Returns a Deployment Policy this Agreement Bot (AgBot) is serving",
             description = "The buspolid should be in the form businessPolOrgid_businessPol. Can be run by the owning User or the AgBot.",
             parameters =
               Array(new Parameter(allowEmptyValue = false,
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
                     new Parameter(allowEmptyValue = false,
                                   allowReserved   = false,
                                   deprecated      = false,
                                   description     = "Deployment Policy identifier",
                                   in              = ParameterIn.PATH,
                                   name            = "deploymentpolicy",
                                   required        = true,
                                   schema          = new Schema(implementation = classOf[String]))
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
  def getDeploymentPolicy(@Parameter(hidden = true) agreementBot: String,
                                  @Parameter(hidden = true) deploymentPolicy: String,
                                  @Parameter(hidden = true) identity: Identity2,
                                  @Parameter(hidden = true) organization: String,
                                  @Parameter(hidden = true) resource: String): Route = {
    Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
    
    val getAgbotManagedDeploymentPolicy: CompiledStreamingExecutable[Query[(AgbotBusinessPols, Rep[String]), (AgbotBusinessPolRow, String), Seq], Seq[(AgbotBusinessPolRow, String)], (AgbotBusinessPolRow, String)] =
      for {
        managedDeploymentPolicy <-
          Compiled(AgbotsTQ.filter(agbot => agbot.id === resource &&
                                   agbot.orgid === organization)
                           .filterIf(identity.isStandardUser)(agbot => agbot.owner === identity.identifier.get || agbot.orgid === "IBM")
                           .filterIf((identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isHubAdmin || identity.isNode || identity.isOrgAdmin)(agbot => agbot.orgid === identity.organization || agbot.orgid === "IBM")
                           .map(_.id)
                           .join(AgbotBusinessPolsTQ.filter(_.busPolId === deploymentPolicy).take(1))
                           .on(_ === _.agbotId)
                           .take(1)
                           .sortBy(policy => (policy._2.businessPolOrgid.asc.nullsDefault, policy._2.businessPol.asc.nullsDefault))
                           .map(policy => (policy._2, policy._2.busPolId)))
      } yield (managedDeploymentPolicy)
    
    complete {
      implicit val formats: Formats = DefaultFormats
      db.run(getAgbotManagedDeploymentPolicy.result.transactionally.asTry).map {
        case Success(result) =>
          Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result size: ${result.size}") }
          
          if(result.nonEmpty)
            (StatusCodes.OK, GetAgbotBusinessPolsResponse(businessPols = (result.map(policies => policies._2 -> new AgbotBusinessPol(policies._1)).toMap)))
          else {
            Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result.nonEmpty:${result.nonEmpty} - ${(StatusCodes.NotFound, Serialization.write(GetAgbotBusinessPolsResponse(businessPols = Map.empty[String, AgbotBusinessPol])))}") }
            (StatusCodes.NotFound, GetAgbotBusinessPolsResponse(businessPols = Map.empty[String, AgbotBusinessPol]))
          }
        case Failure(exception: org.postgresql.util.PSQLException) =>
          Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${Serialization.write(ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage)))}") }
          ExchangePosgtresErrorHandling.ioProblemError(exception, ExchMsg.translate("db.threw.exception", exception.getServerErrorMessage))
        case Failure(exception) =>
          Future { logger.debug(s"GET /orgs/${organization}/agbots/${agreementBot}/businesspols/${deploymentPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, Serialization.write(ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error"))))}") }
          (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
      }
    }
  }
  
  def deploymentPolicyAgreementBot(identity: Identity2): Route =
    path("orgs" / Segment / "agbots" / Segment / "businesspols" / Segment) {
      (organization,
       agreementBot,
       deploymentPolicy) =>
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
                getDeploymentPolicy(agreementBot, deploymentPolicy, identity, organization, resource)
            }
          } ~
          delete {
            exchAuth(TAgbot(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteDeploymentPolicy(agreementBot, deploymentPolicy, identity, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
