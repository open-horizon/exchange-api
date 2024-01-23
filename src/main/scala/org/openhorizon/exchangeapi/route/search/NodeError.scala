package org.openhorizon.exchangeapi.route.search

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.server.Directives.{complete, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, IUser, Identity, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.PostNodeErrorResponse
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.utility.HttpCode
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/search/nodes/error")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait NodeError extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ======== POST /org/{organization}/search/nodes/error ========================
  @POST
  @Operation(summary = "Returns nodes in an error state", description = "Returns a list of the id's of nodes in an error state. Can be run by a user or agbot (but not a node). No request body is currently required.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostNodeErrorResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def postNodeErrorSearch(identity: Identity,
                          organization: String): Route =
  {
    logger.debug(s"Doing POST /orgs/$organization/search/nodes/error")
    
    complete({
      var queryParam = NodesTQ.filter(_.orgid === organization)
      val userId: String = organization + "/" + identity.getIdentity
      identity match {
        case _: IUser => if(!(identity.isSuperUser || identity.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
        case _ => ;
      }
      val q = for {
        (n, _) <- NodeErrorTQ.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
      } yield n.nodeId
      
      db.run(q.result).map({ list =>
        logger.debug("POST /orgs/"+organization+"/search/nodes/error result size: "+list.size)
        val code: StatusCode = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
        (code, PostNodeErrorResponse(list))
      })
    })
  }
  
  val nodeErrorSearch: Route =
    path("orgs" / Segment / "search" / "nodes" / "error") {
      organization =>
        post {
          exchAuth(TNode(OrgAndId(organization,"#").toString),Access.READ) {
            identity =>
              postNodeErrorSearch(identity, organization)
          }
        }
    }
}
