package org.openhorizon.exchangeapi.route.organization

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp.exchAuth
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, TOrg}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/status")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Status extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{organization}/status ================================
  @GET
  @Operation(summary = "Returns summary status of the org", description = "Returns the totals of key resources in the org. Can be run by any id in this org or a hub admin.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgStatusResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getStatus(ident: Identity,
                orgId: String): Route =
    {
      logger.debug(s"GET /orgs/$orgId/status")
      
      complete({
        val statusResp = new OrgStatus()
        //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
        db.run(UsersTQ.getAllUsers(orgId).length.result.asTry.flatMap({
          case Success(v) => statusResp.numberOfUsers = v
            NodesTQ.getAllNodes(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodes = v
            NodeAgreementsTQ.getAgreementsWithState(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeAgreements = v
            NodesTQ.getRegisteredNodesInOrg(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfRegisteredNodes = v
            NodeMsgsTQ.getNodeMsgsInOrg(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeMsgs = v
            SchemaTQ.getSchemaVersion.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => statusResp.dbSchemaVersion = v.head
            statusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, statusResp.toGetOrgStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (t.getMessage.contains("An I/O error occurred while sending to the backend")) (HttpCode.BAD_GW, statusResp.toGetOrgStatusResponse)
            else (HttpCode.INTERNAL_ERROR, statusResp.toGetOrgStatusResponse)
          case Failure(t) => statusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, statusResp.toGetOrgStatusResponse)
        })
      })
    }
  
  /*val statusOrganization: Route =
    path("orgs" / Segment /"status") {
      organization =>
        get {
          exchAuth(TOrg(organization), Access.READ) {
            identity =>
              getStatus(identity, organization)
          }
        }
    }*/
}
