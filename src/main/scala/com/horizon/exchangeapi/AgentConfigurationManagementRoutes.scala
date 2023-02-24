package com.horizon.exchangeapi

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import slick.jdbc.PostgresProfile.api._

import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.DBProcessingError
import com.horizon.exchangeapi.tables.{AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsRequest, AgentVersionsResponse, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ, SchemaTQ, UsersTQ}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody

import java.sql.Timestamp
import java.time.ZoneId
import scala.util.{Failure, Success}

/** Implementation for all of the /orgs/{org}/AgentFileVersion routes */
@Path("/v1/orgs/{orgid}/AgentFileVersion")
@io.swagger.v3.oas.annotations.tags.Tag(name = "agent file version")
trait AgentConfigurationManagementRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  def agentConfigurationManagementRoutes: Route =
    deleteAgentConfigMgmt ~
    getAgentConfigMgmt ~
    putAgentConfigMgmt
  
  // =========== DELETE /orgs/{orgid}/AgentFileVersion ===============================
  @DELETE
  @Path("")
  @Operation(summary = "Delete all agent file versions",
             description = "Delete all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(name = "orgid",
                                              in = ParameterIn.PATH,
                                              description = "Organization id.")),
             responses = Array(new responses.ApiResponse(responseCode = "204", description = "deleted"),
                               new responses.ApiResponse(responseCode = "400", description = "bad input"),
                               new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                               new responses.ApiResponse(responseCode = "403", description = "access denied"),
                               new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & delete) { (orgId) =>
    exchAuth(TOrg("IBM"), Access.WRITE_AGENT_CONFIG_MGMT) { _ =>
      logger.debug(s"DELETE /orgs/$orgId/AgentFileVersion")
      complete({
        orgId match {
          case "IBM" =>
            db.run({
              val versions =
                for {
                  certificate <- AgentCertificateVersionsTQ.delete
                  
                  timestamp: Timestamp = ApiTime.nowUTCTimestamp
                  
                  changed <-
                    if(AgentVersionsChangedTQ.getChanged("IBM").result == 0)
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
                logger.debug("DELETE /v1/orgs/" + "IBM" + "/AgentFileVersions - Certificate Versions: " + result._1)
                logger.debug("DELETE /v1/orgs/" + "IBM" + "/AgentFileVersions - Changed Timestamp: " + result._2)
                logger.debug("DELETE /v1/orgs/" + "IBM" + "/AgentFileVersions - Configuration Versions: " + result._3)
                logger.debug("DELETE /v1/orgs/" + "IBM" + "/AgentFileVersions - Resource Changes: " + result._4)
                logger.debug("DELETE /v1/orgs/" + "IBM" + "/AgentFileVersions - Software Versions: " + result._5)
                
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
          case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", orgId)))
        }
      }) // end of complete
    } // end of exchAuth
  }
  
  // =========== GET /orgs/{orgid}/AgentFileVersion ===============================
  @GET
  @Path("")
  @Operation(summary = "Get all agent file versions",
             description = "Get all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(description = "Organization identifier",
                                              in = ParameterIn.PATH,
                                              name = "orgid")),
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
  def getAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & get) { (orgId) =>
    exchAuth(TOrg("IBM"), Access.READ_AGENT_CONFIG_MGMT) { _ =>
      logger.debug(s"GET /orgs/$orgId/AgentFileVersion")
      complete({
        orgId match {
          case "IBM" =>
            db.run({
              val versions: DBIOAction[(Seq[String], Seq[Timestamp], Seq[String], Seq[String]), NoStream, Effect.Read] =
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
                                                      lastUpdated = result._2.head.toLocalDateTime.atZone(ZoneId.of("UTC")).toString))
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("invalid.input.message", t.toString)))
            })
          case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", orgId)))
        }
      }) // end of complete
    } // end of exchAuth
  }
  
  // =========== PUT /orgs/{orgid}/AgentFileVersion ===============================
  @PUT
  @Path("")
  @Operation(summary = "Put all agent file versions",
             description = "Put all agent certificate, configuration, and software file versions. Run by agreement bot",
             parameters = Array(new Parameter(description = "Organization identifier",
               in = ParameterIn.PATH,
               name = "orgid")),
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
  def putAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & put & entity(as[AgentVersionsRequest])) { (orgId, reqBody) =>
      exchAuth(TOrg("IBM"), Access.WRITE_AGENT_CONFIG_MGMT) { _ =>
        complete({
          orgId match {
            case "IBM" =>
              db.run((AgentCertificateVersionsTQ.delete) andThen
                     (AgentConfigurationVersionsTQ.delete) andThen
                     (AgentSoftwareVersionsTQ.delete) andThen
                     (AgentCertificateVersionsTQ ++= reqBody.agentCertVersions.zipWithIndex.map(certificates => {(certificates._1, orgId, Option(certificates._2.toLong))})) andThen
                     (AgentConfigurationVersionsTQ ++= reqBody.agentConfigVersions.zipWithIndex.map(configurations => {(configurations._1, orgId, Option(configurations._2.toLong))})) andThen
                     (AgentSoftwareVersionsTQ ++= reqBody.agentSoftwareVersions.zipWithIndex.map(software => {(orgId, software._1, Option(software._2.toLong))})) andThen
                     (AgentVersionsChangedTQ.insertOrUpdate((ApiTime.nowUTCTimestamp, orgId))) andThen
                     (ResourceChange(category = ResChangeCategory.ORG,
                                     changeId = 0L,
                                     id = orgId,
                                     operation = ResChangeOperation.MODIFIED,
                                     orgId = orgId,
                                     public = true,
                                     resource = ResChangeResource.AGENTFILEVERSION).insert)
                  .transactionally.asTry.map({
                  case Success(v) =>
                    logger.debug("PUT /orgs/" + orgId + "/AgentFileVersion result: " + v)
                    logger.debug("PUT /orgs/" + orgId + "/AgentFileVersion updating resource status table: " + v)

                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.attr.updated", "AgentFileVersion" ,orgId)))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                      (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.updated", orgId, t.getMessage)))
                    else
                      ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", orgId, t.toString))
                  case Failure(t) =>
                    if (t.getMessage.startsWith("Access Denied:"))
                      (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.updated", orgId, t.getMessage)))
                    else
                      (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, t.toString)))
                })
              )
            case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", orgId)))
          }
        }) // end of complete
        // } // end of validateWithMsg
      } // end of exchAuth
    }
}
