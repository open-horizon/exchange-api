package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.extensions.Extension
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Hidden, Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path, Produces}
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity, Identity2, Role, TAction, TOrg}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.table.agreementbot.agreement.AgbotAgreementsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.AgbotMsgsTQ
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.dbio.DBIOAction
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Compiled

import java.lang.annotation.Annotation
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


trait Status extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  def getStatus(@Parameter(hidden = true) identity: Identity2,
                @Parameter(hidden = true) organization: Option[String] = None): Route = { // Hides fields from being included in the swagger doc as request parameters.
    logger.debug(s"GET /admin/status - By ${identity.resource}:${identity.role}")
    complete({
      val metrics =
        for {
          numOrganizations <-
            Compiled(OrgsTQ.filterOpt(organization)((org, organization) => org.orgid === organization)
                           .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot && !identity.isHubAdmin)(agbots => agbots.orgid === organization.getOrElse(identity.organization) || agbots.orgid === "IBM")
                           .map(_.orgid)
                           .length)
              .result
          
          _ <-
            if (numOrganizations == 0)
              DBIO.failed(new NoSuchElementException(organization.getOrElse("")))
            else
              DBIO.successful(0)
          
          numAgbots <-
            Compiled(AgbotsTQ.filterOpt(organization)((agbots, organization) => agbots.orgid === organization)
                             .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isNode || identity.isStandardUser)(agbots => agbots.orgid === organization.getOrElse(identity.organization) || agbots.orgid === "IBM")
                             //.filterIf(identity.isStandardUser)(agbots => agbots.owner === identity.identifier.get || agbots.orgid === "IBM")
                             .map(agbot => (agbot.id, agbot.orgid))
                             .length)
              .result
          
          numAgbotAgeements <-
            Compiled(AgbotsTQ.filterOpt(organization)((agbot, organization) => agbot.orgid === organization)
                             .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isNode || identity.isStandardUser)(agbots => agbots.orgid === organization.getOrElse(identity.organization) || agbots.orgid === "IBM")
                             //.filterIf(identity.isStandardUser)(agbots => agbots.owner === identity.identifier.get || agbots.orgid === "IBM")
                             .map(agbot => (agbot.id))
                             .join(AgbotAgreementsTQ.map(agreement => (agreement.agrId, agreement.agbotId)))
                             .on((agbot, agreement) => (agbot === agreement._2))
                             .map(result => (result._2._1))
                             .length)
              .result
          
          numAgbotMessages <-
            Compiled(AgbotsTQ.filterOpt(organization)((agbot, organization) => agbot.orgid === organization)
                             .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot) || identity.isNode || identity.isStandardUser)(agbots => agbots.orgid === organization.getOrElse(identity.organization) || agbots.orgid === "IBM")
                             //.filterIf(identity.isStandardUser)(agbots => agbots.owner === identity.identifier.get || agbots.orgid === "IBM")
                             .map(agbot => (agbot.id))
                             .join(AgbotMsgsTQ.map(message => (message.agbotId, message.msgId)))
                             .on((agbot, message) => (agbot === message._1))
                             .map(result => (result._2._2))
                             .length)
              .result
          
          numNodes <-
            if(identity.isHubAdmin)
              DBIO.successful(-1)
            else
              Compiled(NodesTQ.filterOpt(organization)((node, organization) => node.orgid === organization)
                              .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(node => node.orgid === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                              .filterIf(identity.isNode)(_.id === identity.resource)
                              .map(node => (node.id, node.orgid))
                              .length)
                .result
          
          numNodesRegistered <-
            if(identity.isHubAdmin)
              DBIO.successful(-1)
            else
              Compiled(NodesTQ.filterOpt(organization)((org, organization) => org.orgid === organization)
                              .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(node => node.orgid === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                              .filterIf(identity.isNode)(_.id === identity.resource)
                              .filter(nodes => nodes.pattern =!= "" || nodes.publicKey =!= "")
                              .map(node => (node.id, node.orgid))
                              .length)
                .result
          
          numNodesUnregistered <-
            if(identity.isHubAdmin)
              DBIO.successful(-1)
            else
              Compiled(NodesTQ.filterOpt(organization)((node, organization) => node.orgid === organization)
                               .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(node => node.orgid === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                              .filterIf(identity.isNode)(_.id === identity.resource)
                              .filter(nodes => nodes.pattern === "" && nodes.publicKey === "")
                              .map(node => (node.id, node.orgid))
                              .length)
                .result
          
          numNodeAgreements <-
            if(identity.isHubAdmin)
              DBIO.successful(-1)
            else
              Compiled(NodesTQ.filterOpt(organization)((node, organization) => node.orgid === organization)
                              .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(node => node.orgid === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                              .filterIf(identity.isNode)(_.id === identity.resource)
                              .map(node => (node.id))
                              .join(NodeAgreementsTQ.map(agreement => (agreement.agId, agreement.nodeId)))
                              .on((node, agreement) => (node === agreement._2))
                              .map(result => (result._2._1))
                              .length)
                .result
          
          numNodeMessages <-
            if(identity.isHubAdmin)
              DBIO.successful(-1)
            else
              Compiled(NodesTQ.filterOpt(organization)((node, organization) => node.orgid === organization)
                              .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(node => node.orgid === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                              .filterIf(identity.isNode)(_.id === identity.resource)
                              .map(node => (node.id))
                              .join(NodeMsgsTQ.map(message => (message.msgId, message.nodeId)))
                              .on((node, message) => (node === message._2))
                              .map(result => (result._2._1))
                              .length)
                .result
          
          numUsers <-
            if (identity.isAgbot || identity.isNode)
              DBIO.successful(-1)
            else
              Compiled(UsersTQ.filterOpt(organization)((user, organization) => user.organization === organization)
                              .filterIf(!identity.isSuperUser)(users => (users.organization =!= "root" && users.username =!= "root"))
                              .filterIf(identity.isHubAdmin)(users => (users.isHubAdmin || users.isOrgAdmin))
                              .filterIf(identity.isOrgAdmin)(user => user.organization === organization.getOrElse(identity.organization))
                              .filterIf(identity.isStandardUser)(users => users.user === identity.identifier)
                              .map(user => (user.organization, user.username))
                              .length)
                .result
          
          versionSchema <-
            if (identity.isSuperUser || identity.isHubAdmin)
              Compiled(SchemaTQ.filter(_.id === 0)
                               .map(_.schemaversion)).result.head
            else
              DBIO.successful(-1)
        } yield(numAgbotAgeements,
                numAgbotMessages,
                numAgbots,
                numNodeAgreements,
                numNodeMessages,
                numNodes,
                (if (numNodesRegistered == -1) None else Option(numNodesRegistered)),
                (if (numNodesUnregistered == -1) None else Option(numNodesUnregistered)),
                numOrganizations,
                numUsers,
                versionSchema)
      
      db.run(metrics.transactionally.asTry).map {
        case Success(result) =>
          (HttpCode.OK, new AdminStatus(DBMetrics = result, message = ExchMsg.translate("exchange.server.operating.normally"), organization.nonEmpty))
        case Failure(t: org.postgresql.util.PSQLException) =>
          if (t.getMessage.contains("An I/O error occurred while sending to the backend"))
            (HttpCode.BAD_GW, ApiResponse(ApiRespType.BAD_GW, t.getMessage))
          else
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
        case Failure(t: NoSuchElementException) => // Organization not found
          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization.getOrElse(""))))
        case Failure(t) =>
          (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
      }
    })
  }
  
  
  // =========== GET /admin/status ==============================================
  @Path("/v1/admin/status")
  @io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
  @GET
  @Operation(summary = "Returns status of the Exchange server",
             description = """Returns a dictionary of statuses/statistics. Can be run by any user.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[AdminStatus])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def adminStatus(identity: Identity2): Route = {
    path("admin" / "status") {
      get {
        exchAuth(TAction(), Access.STATUS, validIdentity = identity) {
          _ =>
            getStatus(identity = identity)
        }
      }
    }
  }
  
  // =========== GET /orgs/{organization}/status ================================
  @Path("/v1/orgs/{organization}/status")
  @io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
  @GET
  @Operation(description = "Returns the totals of key resources in the org. Can be run by any id in this org or a hub admin.",
             parameters = Array(new Parameter(description = "Organization id.", in = ParameterIn.PATH, name = "organization")),
             responses =
               Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json",
                                                                           schema = new Schema(implementation = classOf[AdminStatus]))),
                                               description = "response body",
                                               responseCode = "200"),
                     new responses.ApiResponse(description = "invalid credentials", responseCode = "401"),
                     new responses.ApiResponse(description = "access denied", responseCode = "403"),
                     new responses.ApiResponse(description = "not found", responseCode = "404")),
             summary = "Returns summary status of the org")
  def orgStatus(identity: Identity2): Route = {
    path("orgs" / Segment /"status") {
      organization =>
        get {
          exchAuth(TOrg(organization), Access.READ, validIdentity = identity) {
            _ =>
              getStatus(identity = identity, organization = Option(organization))
          }
        }
    }
  }
  
  def status(identity: Identity2): Route =
    adminStatus(identity) ~
    orgStatus(identity)
}
