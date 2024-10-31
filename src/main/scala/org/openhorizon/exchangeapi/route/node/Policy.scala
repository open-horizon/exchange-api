package org.openhorizon.exchangeapi.route.node

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, parameter, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.{NodePolicy, NodePolicyTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/nodes/{node}/policy")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/policy")
trait Policy extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/policy ===============================
  @DELETE
  @Operation(summary = "Deletes the policy of a node", description = "Deletes the policy of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deletePolicyNode(@Parameter(hidden = true) node: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route =
    delete {
      complete({
        db.run(NodePolicyTQ.getNodePolicy(resource).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/nodes/" + node + "/policy result: " + v)
            if (v > 0) {
              ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODEPOLICIES, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.policy.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource policy table: " + v)
            NodesTQ.setLastUpdated(resource, ApiTime.nowUTC).asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        })).map({
          case Success(v) => // there were no db policy, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " lastUpdated field updated: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.policy.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/nodes/{node}/policy ================================ */
  @GET
  @Operation(summary = "Returns the node policy", description = "Returns the node run time policy. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[NodePolicy])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getPolicyNode(@Parameter(hidden = true) node: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route =
    complete({
      db.run(NodePolicyTQ.getNodePolicy(resource).result).map({ list =>
        logger.debug("GET /orgs/"+organization+"/nodes/"+node+"/policy result size: "+list.size)
        
        if(list.nonEmpty)
          (HttpCode.OK, list.head.toNodePolicy)
        else
          (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
      })
    })
  
  // =========== PUT /orgs/{organization}/nodes/{node}/policy ===============================
  @PUT
  @Path("")
  @Operation(
    summary = "Adds/updates the node policy",
    description = "Adds or updates the policy of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "ID of the node to be updated."
      ),
      new Parameter(name = "noheartbeat", in = ParameterIn.QUERY, required = false, description = "If set to 'true', skip the step to update the node's lastHeartbeat field.")
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "human readable name of the node policy",
  "description": "descriptive text",
  "properties": [
    {
      "name": "mycommonprop",
      "value": "myservice-testing",
      "type": "string"
    }
  ],
  "constraints": [
    "a == b"
  ],
  "deployment": {
    "properties": [
      {
        "name": "mydeploymentprop",
        "value": "value2",
        "type": "string"
      }
    ],
    "constraints": [
      "c == d"
    ]
  },
  "management": {
    "properties": [
      {
        "name": "mymanagementprop",
        "value": "value3",
        "type": "string"
      }
    ],
    "constraints": [
      "e == f"
    ]
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodePolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(
          new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse]))
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
  def putPolicyNode(@Parameter(hidden = true) node: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route =
    put {
      parameter("noheartbeat".?) {
        noheartbeat =>
          entity(as[PutNodePolicyRequest]) {
            reqBody =>
              validateWithMsg(reqBody.getAnyProblem(noheartbeat)) {
                complete({
                  val noHB = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
                  db.run(reqBody.toNodePolicyRow(resource).upsert.asTry.flatMap({
                    case Success(v) =>
                      logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/policy result: " + v)
                      NodesTQ.setLastUpdated(resource, ApiTime.nowUTC).asTry
                    case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({
                    case Success(v) =>
                      logger.debug("Update /orgs/" + organization + "/nodes/" + node + " lastUpdated result: " + v)
                      if (noHB) DBIO.successful(1).asTry  // skip updating lastHeartbeat
                      else NodesTQ.setLastHeartbeat(resource, ApiTime.nowUTC).asTry
                    case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({
                    case Success(n) =>
                      // Add the resource to the resourcechanges table
                      if (!noHB) logger.debug("Update /orgs/" + organization + "/nodes/" + node + " lastHeartbeat result: " + n)
                      try {
                        val numUpdated: Int = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
                        if (numUpdated > 0) {
                          ResourceChange(0L, organization, node, ResChangeCategory.NODE, false, ResChangeResource.NODEPOLICIES, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                        } else {
                          DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource))).asTry
                        }
                      } catch {
                        case e: Exception => DBIO.failed(new DBProcessingError(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.updated", resource, e))).asTry
                      }
                    case Failure(t) => DBIO.failed(t).asTry
                  })).map({
                    case Success(v) =>
                      logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/policy updating resource status table: " + v)
                      (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.policy.added.or.updated")))
                    case Failure(t: DBProcessingError) =>
                      t.toComplete
                    case Failure(t: org.postgresql.util.PSQLException) =>
                      if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.policy.not.inserted.or.updated", resource, t.getMessage)))
                      else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.policy.not.inserted.or.updated", resource, t.toString))
                    case Failure(t) =>
                      if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.policy.not.inserted.or.updated", resource, t.getMessage)))
                      else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.inserted.or.updated", resource, t.toString)))
                  })
                })
              }
          }
      }
    }
  
  val policyNode: Route =
    path("orgs" / Segment / "nodes" / Segment / "policy") {
      (organization,
       node) =>
        val resource: String = OrgAndId(organization, node).toString
        
        (delete | put) {
          exchAuth(TNode(resource), Access.WRITE) {
            _ =>
              deletePolicyNode(node, organization, resource) ~
              putPolicyNode(node, organization, resource)
          }
        } ~
        get {
          exchAuth(TNode(resource),Access.READ) {
            _ =>
              getPolicyNode(node, organization, resource)
          }
        }
    }
    
}
