/** Services routes for all of the /admin api methods. */
package org.openhorizon.exchangeapi.route.organization

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import jakarta.ws.rs.Path
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, TAction}
import org.openhorizon.exchangeapi.route.administration.DeleteOrgChangesRequest
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util._


//final case class AdminLogLevelRequest(loggingLevel: String)


/** Implementation for all of the /admin routes */
@Path("/v1/orgs/{organization}/changes/cleanup")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Cleanup extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== DELETE /orgs/<orgid>/changes/cleanup ================================ */
  // This route is just for unit testing as a way to clean up the changes table once testing has completed
  // Otherwise the changes table gets clogged with entries in the orgs from testing
  def deleteChanges(organization: String): Route =
    entity(as[DeleteOrgChangesRequest]) {
      reqBody =>
        logger.debug(s"Doing POST /orgs/$organization/changes/cleanup")
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            val resourcesSet: Set[String] = reqBody.resources.toSet
            var action = ResourceChangesTQ.filter(_.orgId === organization).filter(_.id inSet resourcesSet).delete
            if (reqBody.resources.isEmpty) action = ResourceChangesTQ.filter(_.orgId === organization).delete
            db.run(action.transactionally.asTry).map({
              case Success(v) =>
                logger.debug(s"Deleted specified $organization org entries in changes table ONLY FOR UNIT TESTS: $v")
                if (v > 0) (HttpCode.DELETED, ApiResponse(ApiRespType.OK, s"$organization changes deleted"))
                else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", organization)))
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, s"$organization org changes not deleted: " + t.toString)
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, s"$organization org changes not deleted: " + t.toString))
            })
          })
        }
    }
  
  val cleanup: Route =
    path("orgs" / Segment / "changes" / "cleanup") {
      organization =>
        delete {
          exchAuth(TAction(), Access.ADMIN) {
            _ =>
              deleteChanges(organization)
          }
        }
    }
}