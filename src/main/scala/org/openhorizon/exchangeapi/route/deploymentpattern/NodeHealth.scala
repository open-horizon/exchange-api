package org.openhorizon.exchangeapi.route.deploymentpattern

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{POST, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.organization.{NodeHealthHashElement, PostNodeHealthRequest, PostNodeHealthResponse}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.utility.{ApiTime, HttpCode, RouteUtils}
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/deployment/patterns/{pattern}/nodehealth")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
trait NodeHealth extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ======== POST /org/{organization}/deployment/patterns/{deploymentPattern}/nodehealth ========================
  @POST
  @Operation(
    summary = "Returns agreement health of nodes of a particular pattern",
    description = "Returns the lastHeartbeat and agreement times for all nodes that are this pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]",
  "nodeOrgids": ["org1", "org2", "..."]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodeHealthRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """{
  "nodes": {
    "string": {
      "lastHeartbeat": "string",
      "agreements": {
        "string": {
          "lastUpdated": "string"
        }
      }
    }
  }
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[PostNodeHealthResponse])
          )
        )
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
  def postNodeHealth(@Parameter(hidden = true) deploymentPattern: String,
                     @Parameter(hidden = true) identity: Identity2,
                     @Parameter(hidden = true) organization: String): Route =
    entity(as[PostNodeHealthRequest]) {
      reqBody =>
        logger.debug(s"POST /org/${organization}/deployment/patterns/${deploymentPattern}/nodehealth - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            val compositePat: String = OrgAndId(organization, deploymentPattern).toString
            val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(organization)).toSet
            logger.debug("POST /orgs/"+organization+"/deployment/patterns/"+deploymentPattern+"/nodehealth criteria: "+reqBody.toString)
            /*
              Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
              The filter is: n.pattern==ourpattern && n.lastHeartbeat>=lastTime
              Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
            */
            val lastTime: String = if (reqBody.lastTime != "") reqBody.lastTime else Instant.MIN.toString
            val q = for {
              (n, a) <- NodesTQ.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ on (_.id === _.nodeId)
            } yield (n.id, n.lastHeartbeat, a.map(_.agId), a.map(_.lastUpdated))
  
            db.run(q.result).map({ list =>
              logger.debug("POST /orgs/"+organization+"/deployment/patterns/"+deploymentPattern+"/nodehealth result size: "+list.size)
              //logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result: "+list.toString)
              if (list.nonEmpty) (StatusCodes.Created, PostNodeHealthResponse(RouteUtils.buildNodeHealthHash(list)))
              else (StatusCodes.NotFound, PostNodeHealthResponse(Map[String,NodeHealthHashElement]()))
            })
          })
        }
    }
  
  def nodeHealthDeploymentPattern(identity: Identity2): Route =
    path("orgs" / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns") / Segment / "nodehealth") {
      (organization,
       deploymentPattern) =>
        post {
          exchAuth(TNode(OrgAndId(organization,"*").toString), Access.READ, validIdentity = identity) {
            _ =>
              postNodeHealth(deploymentPattern, identity, organization)
          }
        }
    }
}
