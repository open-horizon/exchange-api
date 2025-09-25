package org.openhorizon.exchangeapi.route.search

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, IUser, Identity, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.{PostServiceSearchRequest, PostServiceSearchResponse}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.status.NodeStatusTQ
import org.openhorizon.exchangeapi.utility.HttpCode
import slick.jdbc.PostgresProfile.api._

import java.util
import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/search/nodes/service")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait NodeService extends JacksonSupport with AuthenticationSupport {
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== POST /orgs/{organization}/search/nodes/service  ===============================
  @POST
  @Operation(
    summary = "Returns the nodes a service is running on",
    description = "Returns a list of all the nodes a service is running on. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "orgid": "string",
  "serviceURL": "string",
  "serviceVersion": "string",
  "serviceArch": "string"
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostServiceSearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body:",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """{
  "nodes": [
    {
      "string": "string",
      "string": "string"
    }
  ]
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[PostServiceSearchResponse])
          )
        )
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  def postNodeServiceSearch(@Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String): Route =
    entity(as[PostServiceSearchRequest]) {
      reqBody =>
        logger.debug(s"POST /orgs/$organization/search/nodes/service - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            val service: String = reqBody.serviceURL + "_" + reqBody.serviceVersion + "_" + reqBody.serviceArch
            logger.debug("POST /orgs/"+organization+"/search/nodes/service criteria: "+reqBody.toString)
            val orgService: String = "%|" + reqBody.orgid + "/" + service + "|%"
            var qFilter = NodesTQ.filter(_.orgid === organization)
            if (identity.isUser) {
              // if the caller is a normal user then we need to only return node the caller owns
              if(!(identity.isSuperUser || identity.isOrgAdmin)) qFilter = qFilter.filter(_.owner === identity.identifier)
            }// nodes can't call this route and agbots don't need an additional filter
            val q = for {
              (n, _) <- qFilter join (NodeStatusTQ.filter(_.runningServices like orgService)) on (_.id === _.nodeId)
            } yield (n.id, n.lastHeartbeat)
            
            db.run(q.result).map({ list =>
              logger.debug("POST /orgs/"+organization+"/services/"+service+"/search result size: "+list.size)
              val code: StatusCode = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
              (code, PostServiceSearchResponse(list))
            })
          })
      }
  }
  
  def nodeServiceSearch(identity: Identity2): Route =
    path("orgs" / Segment / "search" / "nodes" / "service") {
      organization =>
        post {
          exchAuth(TNode(OrgAndId(organization,"#").toString),Access.READ, validIdentity = identity) {
            _ =>
              postNodeServiceSearch(identity, organization)
          }
        }
    }
}
