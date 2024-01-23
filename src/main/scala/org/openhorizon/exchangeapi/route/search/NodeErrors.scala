package org.openhorizon.exchangeapi.route.search

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, IUser, Identity, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.organization.{AllNodeErrorsInOrgResp, NodeErrorsResp}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.utility.HttpCode
import slick.jdbc.PostgresProfile.api._

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/search/nodes/error/all")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait NodeErrors extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{organization}/search/nodes/error/all ================================
  @GET
  @Path("")
  @Operation(summary = "Returns all node errors", description = "Returns a list of all the node errors for an organization (that the caller has access to see) in an error state. Can be run by a user or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[AllNodeErrorsInOrgResp])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getNodeErrorsSearch(identity: Identity,
                          organization: String): Route =
    {
      logger.debug(s"Doing GET /orgs/$organization/search/nodes/error/all")
      
      complete({
        var queryParam = NodesTQ.filter(_.orgid === organization)
        val userId: String = organization + "/" + identity.getIdentity
        identity match {
          case _: IUser => if(!(identity.isSuperUser || identity.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
          case _ => ;
        }
        val q = for {
          (ne, _) <- NodeErrorTQ.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
        } yield (ne.nodeId, ne.errors, ne.lastUpdated)
        
        db.run(q.result).map({
          list =>
            logger.debug("GET /orgs/"+organization+"/search/nodes/error/all result size: "+list.size)
            if (list.nonEmpty) {
              val errorsList: ListBuffer[NodeErrorsResp] = ListBuffer[NodeErrorsResp]()
              for ((nodeId, errorsString, lastUpdated) <- list) { errorsList += NodeErrorsResp(nodeId, errorsString, lastUpdated)}
              (HttpCode.OK, AllNodeErrorsInOrgResp(errorsList))
            }
            else
              (HttpCode.OK, AllNodeErrorsInOrgResp(ListBuffer[NodeErrorsResp]()))
        })
      })
  }
  
  val nodeErrorsSearch: Route =
    path("orgs" / Segment / "search"  / "nodes" / "error" / "all") {
      organization =>
        get {
          exchAuth(TNode(OrgAndId(organization,"#").toString),Access.READ) {
            identity =>
              getNodeErrorsSearch(identity, organization)
          }
        }
    }
}
