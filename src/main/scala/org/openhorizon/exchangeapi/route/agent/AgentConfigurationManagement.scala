package org.openhorizon.exchangeapi.route.agent

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, TOrg}
import org.openhorizon.exchangeapi.route.agreementbot.PutAgbotsRequest
import org.openhorizon.exchangeapi.table.agent.certificate.AgentCertificateVersionsTQ
import org.openhorizon.exchangeapi.table.agent.configuration.AgentConfigurationVersionsTQ
import org.openhorizon.exchangeapi.table.agent.software.AgentSoftwareVersionsTQ
import org.openhorizon.exchangeapi.table.agent.{AgentVersionsChangedTQ, AgentVersionsRequest, AgentVersionsResponse}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Implementation for all of the /orgs/{org}/AgentFileVersion routes */
@Path("/v1/orgs/{organization}/AgentFileVersion")
@io.swagger.v3.oas.annotations.tags.Tag(name = "agent file version")
trait AgentConfigurationManagement extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/AgentFileVersion ===============================
  @DELETE
  @Operation(summary = "Delete all agent file versions",
             description = "Delete all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(name = "organization",
                                              in = ParameterIn.PATH,
                                              description = "Organization id.")),
             responses = Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                               new responses.ApiResponse(responseCode = "400", description = "bad input"),
                               new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                               new responses.ApiResponse(responseCode = "403", description = "access denied"),
                               new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteAgentConfigMgmt(@Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String): Route =
    delete {
      Future { logger.debug(s"DELETE /orgs/$organization/AgentFileVersion - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
      complete({
        organization match {
          case "IBM" =>
            db.run({
              val versions =
                for {
                  certificate <- AgentCertificateVersionsTQ.delete
                  
                  timestamp: Instant = ApiTime.nowUTCTimestamp

                  checkAgentVersionsResult <- AgentVersionsChangedTQ.getChanged("IBM").result

                  changed <-
                    if(checkAgentVersionsResult == 0)
                      AgentVersionsChangedTQ += (timestamp, "IBM")
                    else
                      AgentVersionsChangedTQ.getChanged("IBM").update(timestamp)

                  configuration <- AgentConfigurationVersionsTQ.delete
                  resource <- ResourceChangesTQ += ResourceChangeRow(category = ResChangeCategory.ORG.toString,
                                                                     changeId = 0L,
                                                                     id = "IBM",
                                                                     lastUpdated = timestamp,
                                                                     operation = ResChangeOperation.MODIFIED.toString,
                                                                     orgId = "IBM",
                                                                     public = "true",
                                                                     resource = ResChangeResource.AGENTFILEVERSION.toString)
                  software <- AgentSoftwareVersionsTQ.delete
                } yield (certificate, changed, configuration, resource, software)
              
              versions.transactionally.asTry}).map({
              
              case Success(result) =>
                Future { logger.debug(s"DELETE /v1/orgs/IBM/AgentFileVersions - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Certificate Versions:   ${result._1}") }
                Future { logger.debug(s"DELETE /v1/orgs/IBM/AgentFileVersions - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Changed Timestamp:      ${result._2}") }
                Future { logger.debug(s"DELETE /v1/orgs/IBM/AgentFileVersions - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Configuration Versions: ${result._3}") }
                Future { logger.debug(s"DELETE /v1/orgs/IBM/AgentFileVersions - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Resource Changes:       ${result._4}") }
                Future { logger.debug(s"DELETE /v1/orgs/IBM/AgentFileVersions - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - Software Versions:      ${result._5}") }
                
                if((0 <= result._1 ||
                    0 <= result._3 ||
                    0 <= result._5) &&
                   result._2 == 1 &&
                   result._4 == 1) {
                  (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("agent.file.versions.deleted")))
                }
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agent.file.versions.deleted.not")))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("agent.file.versions.deleted.not", t.toString))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("agent.file.versions.deleted.not.message", t.toString)))
            })
          case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", organization)))
        }
      })
    }
  
  // =========== GET /orgs/{organization}/AgentFileVersion ===============================
  @GET
  @Operation(summary = "Get all agent file versions",
             description = "Get all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(description = "Organization identifier",
                                              in = ParameterIn.PATH,
                                              name = "organization")),
             responses = Array(new responses.ApiResponse(responseCode = "200", description = "response body",
               content = Array(
                 new Content(
                   examples = Array(
                     new ExampleObject(
                       value ="""{
  "agentSoftwareVersions": ["2.30.0", "2.25.4", "1.4.5-123"],
  "agentConfigVersions": ["0.0.4", "0.0.3"],
  "agentCertVersions": ["0.0.15"],
  "lastUpdated":""
}
                              """
                     )
                   ),
                   mediaType = "application/json",
                   schema = new Schema(implementation = classOf[AgentVersionsResponse])
                 ))),
                               new responses.ApiResponse(responseCode = "400", description = "bad input"),
                               new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                               new responses.ApiResponse(responseCode = "403", description = "access denied"),
                               new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getAgentConfigMgmt(@Parameter(hidden = true) identity: Identity2,
                         @Parameter(hidden = true) orgId: String): Route =
    {
      Future { logger.debug(s"GET /orgs/$orgId/AgentFileVersion - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
      complete({
        orgId match {
          case "IBM" =>
            db.run({
              val versions: DBIOAction[(Seq[String], Seq[Instant], Seq[String], Seq[String]), NoStream, Effect.Read] =
                for {
                certificate <- AgentCertificateVersionsTQ.sortBy(_.priority.asc.nullsLast).filter(_.organization === "IBM").map(_.certificateVersion).result
                changed <- AgentVersionsChangedTQ.getChanged("IBM").sortBy(_.desc).result
                configuration <- AgentConfigurationVersionsTQ.sortBy(_.priority.asc.nullsLast).filter(_.organization === "IBM").map(_.configurationVersion).result
                software <- AgentSoftwareVersionsTQ.sortBy(_.priority.asc.nullsLast).filter(_.organization === "IBM").map(_.softwareVersion).result
              } yield (certificate, changed, configuration, software)
            
            versions.transactionally.asTry}).map({
              case Success(result) =>
                if(0 < result._2.length &&
                   result._2.sizeIs < 2)
                  (HttpCode.OK, AgentVersionsResponse(agentCertVersions = result._1,
                                                      agentConfigVersions = result._3,
                                                      agentSoftwareVersions = result._4,
                                                      lastUpdated = result._2.head.toString))
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("invalid.input.message", t.toString)))
            })
          case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", orgId)))
        }
      })
    }
  
  // =========== PUT /orgs/{organization}/AgentFileVersion ===============================
  @PUT
  @Operation(summary = "Put all agent file versions",
             description = "Put all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(description = "Organization identifier",
               in = ParameterIn.PATH,
               name = "organization")),
             requestBody = new RequestBody(
               content = Array(
                 new Content(
                   examples = Array(
                     new ExampleObject(
                       value = """{
  "agentSoftwareVersions": ["2.30.0", "2.25.4", "1.4.5-123"],
  "agentConfigVersions": ["0.0.4", "0.0.3"],
  "agentCertVersions": ["0.0.15"]
}
                       """
                     )
                   ),
                   mediaType = "application/json",
                   schema = new Schema(implementation = classOf[PutAgbotsRequest])
                 )
               ),
               required = true
             ),
             responses =
               Array(
                 new responses.ApiResponse(responseCode = "201",
                                           description = "response body",
                                           content =
                                             Array(
                                               new Content(mediaType = "application/json",
                                                           schema = new Schema(implementation = classOf[ApiResponse])
                                               )
                                             )
                 ),
                 new responses.ApiResponse(responseCode = "400", description = "bad input"),
                 new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                 new responses.ApiResponse(responseCode = "403", description = "access denied"),
                 new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putAgentConfigMgmt(@Parameter(hidden = true) identity: Identity2,
                         @Parameter(hidden = true) organization: String): Route =
    put {
      entity(as[AgentVersionsRequest]) {
        reqBody =>
          Future { logger.debug(s"PUT /orgs/$organization/AgentFileVersion - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
          
          val INSTANT: Instant = Instant.now()
          
          complete({
            organization match {
             case "IBM" =>
               db.run((AgentCertificateVersionsTQ.delete) andThen
                      (AgentConfigurationVersionsTQ.delete) andThen
                      (AgentSoftwareVersionsTQ.delete) andThen
                      (AgentCertificateVersionsTQ ++= reqBody.agentCertVersions.zipWithIndex.map(certificates => {(certificates._1, organization, Option(certificates._2.toLong))})) andThen
                      (AgentConfigurationVersionsTQ ++= reqBody.agentConfigVersions.zipWithIndex.map(configurations => {(configurations._1, organization, Option(configurations._2.toLong))})) andThen
                      (AgentSoftwareVersionsTQ ++= reqBody.agentSoftwareVersions.zipWithIndex.map(software => {(organization, software._1, Option(software._2.toLong))})) andThen
                      (AgentVersionsChangedTQ.insertOrUpdate((ApiTime.nowUTCTimestamp, organization))) andThen
                      (resourcechange.ResourceChange(category = ResChangeCategory.ORG,
                                                     changeId = 0L,
                                                     id = organization,
                                                     lastUpdated = INSTANT,
                                                     operation = ResChangeOperation.MODIFIED,
                                                     orgId = organization,
                                                     public = true,
                                                     resource = ResChangeResource.AGENTFILEVERSION).insert)
                        .transactionally.asTry.map({
                          case Success(v) =>
                            Future { logger.debug("PUT /orgs/" + organization + "/AgentFileVersion result: " + v) }
                            Future { logger.debug("PUT /orgs/" + organization + "/AgentFileVersion updating resource status table: " + v) }
        
                            (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.attr.updated", "AgentFileVersion" ,organization)))
                          case Failure(t: org.postgresql.util.PSQLException) =>
                            if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.updated", organization, t.getMessage)))
                            else
                              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", organization, t.toString))
                          case Failure(t) =>
                            if (t.getMessage.startsWith("Access Denied:"))
                              (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.updated", organization, t.getMessage)))
                            else
                              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", organization, t.toString)))
                        })
               )
             case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", organization)))
            }
          })
      }
    }
  
  def agentConfigurationManagement(identity: Identity2): Route =
    path("orgs" / Segment / "AgentFileVersion") {
      organization =>
        (delete | put) {
          exchAuth(TOrg("IBM"), Access.WRITE_AGENT_CONFIG_MGMT, validIdentity = identity) {
            _ =>
              deleteAgentConfigMgmt(identity, organization) ~
              putAgentConfigMgmt(identity, organization)
          }
        } ~
        get {
          exchAuth(TOrg("IBM"), Access.READ_AGENT_CONFIG_MGMT, validIdentity = identity) {
            _ =>
              getAgentConfigMgmt(identity, organization)
          }
        }
    }
}
