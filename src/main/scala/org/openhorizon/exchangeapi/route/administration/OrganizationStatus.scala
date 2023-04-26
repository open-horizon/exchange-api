package org.openhorizon.exchangeapi.route.administration

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives.{complete, get, path, pathPrefix, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, responses}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.{Access, AuthenticationSupport, ExchMsg, HttpCode, TAction}
import org.openhorizon.exchangeapi.table.NodesTQ
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
  @Operation(summary = "Returns the org-specific status of the Exchange server",
             description = """Returns a dictionary of statuses/statistics. Can be run by superuser, hub admins, and org admins.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[GetAdminOrgStatusResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def getOrganizationStatus: Route = {
    logger.debug("Doing GET /admin/orgstatus")
    complete({
      val orgStatusResp = new AdminOrgStatus()
      // perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
      val q =
        for {
          n <- NodesTQ.groupBy(_.orgid)
        } yield (n._1, n._2.length) // this should returin [orgid, num of nodes in that orgid]
      
      db.run(q.result.asTry)
        .map({
          case Success(nodes) => // nodes : Seq[(String, Int)]
            orgStatusResp.nodesByOrg = nodes.toMap
            orgStatusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, orgStatusResp.toGetAdminOrgStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            orgStatusResp.msg = t.getMessage
            if (t.getMessage.contains("An I/O error occurred while sending to the backend"))
              (HttpCode.BAD_GW, orgStatusResp.toGetAdminOrgStatusResponse)
            else
              (HttpCode.INTERNAL_ERROR, orgStatusResp.toGetAdminOrgStatusResponse)
          case Failure(t) =>
            orgStatusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, orgStatusResp.toGetAdminOrgStatusResponse)
        })
    })
  }
  
  
  val organizationStatus: Route =
    path("admin" / "orgstatus") {
      get {
        exchAuth(TAction(), Access.ORGSTATUS) {
          _ =>
            getOrganizationStatus
        }
      }
    }
}
