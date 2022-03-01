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
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

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
  
  def getAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & get) { (orgid) =>
  
  }
  
  def putAgentConfigMgmt: Route = (path("orgs" / Segment / "AgentFileVersion") & put) { (orgid) =>
  
  }
}
