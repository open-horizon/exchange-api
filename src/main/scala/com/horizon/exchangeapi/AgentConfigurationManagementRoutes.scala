package com.horizon.exchangeapi

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Route
import slick.jdbc.PostgresProfile.api._

import javax.ws.rs.Path
import scala.concurrent.ExecutionContext
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.tables.{AgentCertificateVersionsTQ, AgentConfigurationVersionsTQ, AgentSoftwareVersionsTQ, AgentVersionsChangedTQ, AgentVersionsResponse, NodeAgreementsTQ, NodeMsgsTQ, NodesTQ, SchemaTQ, UsersTQ}
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import java.sql.Timestamp
import scala.util.{Failure, Success}

/** Implementation for all of the /orgs/{org}/AgentFileVersion routes */
@Path("/v1/orgs/{orgid}/AgentFileVersion")
@io.swagger.v3.oas.annotations.tags.Tag(name = "agent-configuration-management")
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
  
  def deleteAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & delete) { (orgid) =>
  
  }
  
  def getAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & get) { (orgId) =>
    exchAuth(TOrg("IBM"), Access.READ) { _ =>
      logger.debug(s"GET /orgs/$orgId/AgentFileVersion")
      complete({
        orgId match {
          case "IBM" =>
            db.run({
              val versions: DBIOAction[(Seq[String], Seq[Timestamp], Seq[String], Seq[String]), NoStream, Effect.Read] =
                for {
                certificate <- AgentCertificateVersionsTQ.getAgentCertificateVersions("IBM").sortBy(_.desc).result
                changed <- AgentVersionsChangedTQ.getChanged("IBM").sortBy(_.desc).result
                configuration <- AgentConfigurationVersionsTQ.getAgentConfigurationVersions("IBM").sortBy(_.desc).result
                software <- AgentSoftwareVersionsTQ.getAgentSoftwareVersions("IBM").result
              } yield (certificate, changed, configuration, software)
            
            versions.transactionally.asTry}).map({
              case Success(result) =>
                if(0 < result._2.length &&
                   result._2.sizeIs < 2)
                  (HttpCode.OK, AgentVersionsResponse(agentCertVersions = result._1,
                                                      agentConfigVersions = result._3,
                                                      agentSoftwareVersions = result._4,
                                                      lastUpdated = result._2.head))
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
            })
          case _ => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", orgId)))
        }
      }) // end of complete
    } // end of exchAuth
  }
  
  def putAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & put) { (orgid) =>
    
  }
}
