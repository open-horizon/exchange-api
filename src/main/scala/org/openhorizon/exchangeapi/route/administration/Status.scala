package org.openhorizon.exchangeapi.route.administration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, responses}
import jakarta.ws.rs.{GET, Path}
import org.checkerframework.checker.units.qual.t
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, TAction}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.table.agreementbot.agreement.AgbotAgreementsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.AgbotMsgsTQ
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.utility.{ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/admin/status")
@io.swagger.v3.oas.annotations.tags.Tag(name = "administration")
trait Status extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== GET /admin/status ===============================
  @GET
  @Operation(summary = "Returns status of the Exchange server",
             description = """Returns a dictionary of statuses/statistics. Can be run by any user.""",
             responses =
               Array(new responses.ApiResponse(responseCode = "200", description = "response body",
                                               content =
                                                 Array(new Content(mediaType = "application/json",
                                                                   schema = new Schema(implementation = classOf[GetAdminStatusResponse])))),
                     new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
                     new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def getStatus: Route = {
    logger.debug("Doing GET /admin/status")
    complete({
      val statusResp = new AdminStatus() //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
      db.run(
        UsersTQ.length.result.asTry
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfUsers = v
                   NodesTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfNodes = v
                   AgbotsTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfAgbots = v
                   NodeAgreementsTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfNodeAgreements = v
                   AgbotAgreementsTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfAgbotAgreements = v
                   NodeMsgsTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfNodeMsgs = v
                   AgbotMsgsTQ.length.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry})
               .flatMap({
                 case Success(v) =>
                   statusResp.numberOfAgbotMsgs = v
                   SchemaTQ.getSchemaVersion.result.asTry
                 case Failure(t) =>
                   DBIO.failed(t).asTry}))
        .map({
          case Success(v) =>
            statusResp.dbSchemaVersion = v.head
            statusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, statusResp.toGetAdminStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (t.getMessage.contains("An I/O error occurred while sending to the backend"))
              (HttpCode.BAD_GW, statusResp.toGetAdminStatusResponse)
            else
              (HttpCode.INTERNAL_ERROR, statusResp.toGetAdminStatusResponse)
          case Failure(t) =>
            statusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, statusResp.toGetAdminStatusResponse)
        })
    })
  }
  
  val status: Route =
    path("admin" / "status") {
      get {
        exchAuth(TAction(), Access.STATUS) {
          _ =>
            getStatus
        }
      }
    }
}
