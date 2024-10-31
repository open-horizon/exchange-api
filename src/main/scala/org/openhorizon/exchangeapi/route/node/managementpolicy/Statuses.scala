package org.openhorizon.exchangeapi.route.node.managementpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.{GetNMPStatusResponse, NodeMgmtPolStatuses}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext


@Path("/v1/orgs/{organization}/nodes/{node}/managementStatus")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
trait Statuses extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /orgs/{organization}/nodes/{node}/managementStatus ================================ */
  @GET
  @Operation(summary = "Returns status for nodeid", description = "Returns the management status of the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """{
      "managementStatus": {
        "mgmtpolicy1": {
          "agentUpgradePolicyStatus": {
            "scheduledTime": "<RFC3339 timestamp>",
            "startTime": "<RFC3339 timestamp>",
            "endTime": "<RFC3339 timestamp>",
            "upgradedVersions": {
              "softwareVersion": "1.1.1",
              "certVersion": "2.2.2",
              "configVersion": "3.3.3"
            },
            "status":  "success|failed|in progress",
            "errorMessage": "Upgrade process failed",
            "lastUpdated": ""
          }
  },
        "mgmtpolicy2": {
          "agentUpgradePolicyStatus": {
            "scheduledTime": "<RFC3339 timestamp>",
            "startTime": "<RFC3339 timestamp>",
            "endTime": "<RFC3339 timestamp>",
            "upgradedVersions": {
              "softwareVersion": "1.1.1",
              "certVersion": "2.2.2",
              "configVersion": "3.3.3"
            },
            "status":  "success|failed|in progress",
            "errorMessage": "Upgrade process failed",
            "lastUpdated": ""
          }
        }
},
    "lastIndex": "0"
}
                      """
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNMPStatusResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getStatusManagementPolicies(@Parameter(hidden = true) node: String,
                                  @Parameter(hidden = true) organization: String): Route =
    complete({
      var q = NodeMgmtPolStatuses.getNodeMgmtPolStatuses(organization + "/" + node).sortBy(_.policy.asc.nullsFirst)
      db.run(q.result).map({ list =>
        logger.debug(s"GET /orgs/$organization/nodes/$node/managementStatus result size: "+list.size)
        val code: StatusCode =
          if(list.nonEmpty)
            StatusCodes.OK
          else
            StatusCodes.NotFound
        (code, GetNMPStatusResponse(list))
      })
    })
  
  val statuses: Route =
    path("orgs" / Segment / "nodes" / Segment / "managementStatus") {
      (organization, node) =>
        val compositeId: String = OrgAndId(organization, node).toString
        
        get {
          exchAuth(TNode(compositeId),Access.READ) {
            _ =>
              getStatusManagementPolicies(node, organization)
          }
        }
    }
}
