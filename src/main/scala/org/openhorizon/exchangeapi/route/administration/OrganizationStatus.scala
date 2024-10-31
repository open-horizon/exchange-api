package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity, TAction}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
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
  def getOrganizationStatus(@Parameter(hidden = true) identity: Identity): Route = { // Hides fields from being included in the swagger doc as request parameters.
    logger.debug("Doing GET /admin/status")
    complete({
      val metrics =
        for {
          numNodesByOrg <-
            if(!identity.isSuperUser && identity.isHubAdmin)
              Compiled(OrgsTQ.map(organization => (organization.orgid, -1))
                             .sortBy(_._1))
                .result
            else {
              Compiled(OrgsTQ.filterIf(!(identity.isSuperUser || identity.isMultiTenantAgbot))(organization => (organization.orgid === identity.getOrg || organization.orgid === "IBM"))
                             .filterIf(identity.role.equals(AuthRoles.Node))(_.orgid === identity.identityString)
                             .map(_.orgid)
                             .joinLeft(NodesTQ.filterIf(!(identity.isSuperUser || identity.isMultiTenantAgbot))(node => (node.orgid === identity.getOrg || node.orgid === "IBM"))
                                              .filterIf(!(identity.isAdmin || identity.isHubAdmin) && identity.role.equals(AuthRoles.User))(_.owner === identity.identityString)
                                              .filterIf(identity.role.equals(AuthRoles.Node))(_.id === identity.identityString)
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
          (HttpCode.OK, new AdminOrgStatus(msg = ExchMsg.translate("exchange.server.operating.normally"), nodes = result.toMap))
        case Failure(t: org.postgresql.util.PSQLException) =>
          if (t.getMessage.contains("An I/O error occurred while sending to the backend"))
            (HttpCode.BAD_GW, ApiResponse(ApiRespType.BAD_GW, t.getMessage))
          else
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
        case Failure(t) =>
          (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, t.getMessage))
      }
    })
  }
  
  
  val organizationStatus: Route =
    path("admin" / "orgstatus") {
      get {
        exchAuth(TAction(), Access.ORGSTATUS) {
          identity =>
            getOrganizationStatus(identity = identity)
        }
      }
    }
}
