package org.openhorizon.exchangeapi.route.search

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AccessDeniedException, AuthenticationSupport, IUser, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.organization.{AllNodeErrorsInOrgResp, NodeErrorsResp}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.utility.HttpCode
import slick.jdbc.PostgresProfile.api._

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
  def getNodeErrorsSearch(@Parameter(hidden = true) identity: Identity2,
                          @Parameter(hidden = true) organization: String): Route = {
    logger.debug(s"GET /orgs/$organization/search/nodes/error/all - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
    
    val searchNodeErrors =
      for {
        nodeErrors <-
                    NodeErrorTQ.filter(_.errors =!= "")
                              .filter(_.errors =!= "[]")
                              .join(NodesTQ.filter(_.orgid === organization)
                                           .filterIf(identity.isStandardUser)(_.owner === identity.identifier.get)
                                           .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(_.orgid === identity.organization)
                                           .map(_.id))
                              .on(_.nodeId === _)
                              .map(nodeErrors =>
                                    (nodeErrors._1.nodeId,
                                     nodeErrors._1.errors,
                                     nodeErrors._1.lastUpdated))
        
      } yield nodeErrors.mapTo[NodeErrorsResp]
      
      complete{
        db.run(Compiled(searchNodeErrors).result.transactionally).map {
          nodeErrors =>
            logger.debug(s"GET /orgs/$organization/search/nodes/error/all - result size: ${nodeErrors.size}")
            if (nodeErrors.nonEmpty) {
              (StatusCodes.OK, AllNodeErrorsInOrgResp(nodeErrors))
            }
            else
              (StatusCodes.OK, AllNodeErrorsInOrgResp(Seq.empty[NodeErrorsResp]))
        }
      }
  }
  
  def nodeErrorsSearch(identity: Identity2): Route =
    path("orgs" / Segment / "search"  / "nodes" / "error" / "all") {
      organization =>
        get {
          exchAuth(TNode(OrgAndId(organization,"#").toString),Access.READ, validIdentity = identity) {
            _ =>
              getNodeErrorsSearch(identity, organization)
          }
        }
    }
}
