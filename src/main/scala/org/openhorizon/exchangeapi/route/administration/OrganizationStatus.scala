package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity, Identity2, TAction}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/admin/orgstatus")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait OrganizationStatus extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== GET /admin/orgstatus ===============================
  @GET
  @Operation(description = """Returns a dictionary of statuses/statistics. Can be run by superuser, hub admins, and org admins.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[AdminOrgStatus])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")),
             summary = "Returns the org-specific status of the Exchange server")
  def getOrganizationStatus(@Parameter(hidden = true) identity: Identity2): Route = { // Hides fields from being included in the swagger doc as request parameters.
    logger.debug(s"GET /admin/status - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    complete({
      val metrics =
        for {
          numNodesByOrg <-
            if(identity.role != AuthRoles.SuperUser &&
              identity.role == AuthRoles.HubAdmin)
              Compiled(OrgsTQ.map(organization => (organization.orgid, -1))
                             .sortBy(_._1))
                .result
            else {
              Compiled(OrgsTQ.filterIf(!(identity.role == AuthRoles.SuperUser || identity.isMultiTenantAgbot))(organization => (organization.orgid === identity.organization || organization.orgid === "IBM"))
                             .filterIf(identity.isNode)(_.orgid === identity.organization)
                             .map(_.orgid)
                             .joinLeft(NodesTQ.filterIf(!(identity.role == AuthRoles.SuperUser || identity.isMultiTenantAgbot))(node => (node.orgid === identity.organization || node.orgid === "IBM"))
                                              .filterIf(!(identity.role == AuthRoles.AdminUser || identity.role == AuthRoles.HubAdmin) && identity.isUser)(_.owner === identity.identifier.get)
                                              .filterIf(identity.isNode)(_.id === identity.resource)
                                              .groupBy(node => node.orgid)
                                              .map{case (orgid, group) => (orgid, group.map(_.id).length)})
                             .on((organization, node) => (organization === node._1))
                             .map(result => (result._1, result._2.getOrElse(("", 0))._2))
                             .sortBy(_._1.asc))
                .result
            }
        } yield(numNodesByOrg)
      
      db.run(metrics.transactionally.asTry).map {
        case Success(result) =>
          (StatusCodes.OK, new AdminOrgStatus(msg = ExchMsg.translate("exchange.server.operating.normally"), nodes = result.toMap))
        case Failure(t: org.postgresql.util.PSQLException) =>
          if (t.getMessage.contains("An I/O error occurred while sending to the backend"))
            (StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, t.getMessage))
          else
            (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
        case Failure(t) =>
          (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
      }
    })
  }
  
  
  def organizationStatus(identity: Identity2): Route =
    path("admin" / "orgstatus") {
      get {
        exchAuth(TAction(), Access.ORGSTATUS, validIdentity = identity) {
          _ =>
            getOrganizationStatus(identity = identity)
        }
      }
    }
}
