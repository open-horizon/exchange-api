/** Services routes for all of the /orgs/{organization}/nodes api methods. */
package org.openhorizon.exchangeapi.route.node

import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.public
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ArraySchema, Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext
import org.openhorizon.exchangeapi.table._
import org.json4s._
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.util._
import scala.util.control.Breaks._
import scala.util.matching.Regex
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.{OneUserInputService, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.{NodeAgreement, NodeAgreementsTQ}
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.{NodePolicy, NodePolicyTQ}
import org.openhorizon.exchangeapi.table.node.error.{NodeError, NodeErrorTQ}
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.{GetNMPStatusResponse, NodeMgmtPolStatusRow, NodeMgmtPolStatuses}
import org.openhorizon.exchangeapi.table.node.message.{NodeMsg, NodeMsgRow, NodeMsgsTQ}
import org.openhorizon.exchangeapi.table.node.status.{NodeStatus, NodeStatusTQ}
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodesTQ, OneService, RegService}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth, StrConstants}

import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.language.postfixOps


/** Implementation for all of the /orgs/{organization}/nodes routes */
@Path("/v1/orgs/{organization}")
trait NodesRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def nodesRoutes: Route = nodeDeleteAgreementRoute ~
                           nodeDeleteAgreementsRoute ~
                           nodeDeleteErrorsRoute ~
                           nodeDeleteMsgRoute ~
                           nodeDeletePolicyRoute ~
                           nodeDeleteStatusRoute ~
                           nodeGetAgreementRoute ~
                           nodeGetAgreementsRoute ~
                           nodeGetAllMgmtPolStatus ~
                           nodeGetErrorsRoute ~
                           nodeGetMsgRoute ~
                           nodeGetMsgsRoute ~
                           nodeGetPolicyRoute ~
                           nodeGetStatusRoute ~
                           nodeGetMgmtPolStatus ~
                           nodeDeleteMgmtPolStatus ~
                           nodeHeartbeatRoute ~
                           nodePostConfigStateRoute ~
                           nodePostMsgRoute ~
                           nodePutAgreementRoute ~
                           nodePutErrorsRoute ~
                           nodePutMgmtPolStatus ~
                           nodePutPolicyRoute ~
                           nodePutStatusRoute ~
                           nodesGetDetails
  
  // =========== POST /orgs/{organization}/nodes/{node}/services_configstate ===============================
  @POST
  @Path("nodes/{node}/services_configstate")
  @Operation(
    summary = "Changes config state of registered services",
    description = "Suspends (or resumes) 1 or more services on this edge node. Can be run by the node owner or the node.",
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
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "org": "myorg",
  "url": "myserviceurl",
  "configState": "suspended",
  "version": "1.0.0"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodeConfigStateRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def nodePostConfigStateRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "services_configstate") & post & entity(as[PostNodeConfigStateRequest])) { (orgid, id, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          db.run(NodesTQ.getRegisteredServices(compositeId).result.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/configstate result: " + v)
              if (v.nonEmpty) reqBody.getDbUpdate(v.head, compositeId).asTry // pass the update action to the next step
              else DBIO.failed(new Throwable("Invalid Input: node " + compositeId + " not found")).asTry // it seems this returns success even when the node is not found
            case Failure(t) => DBIO.failed(t).asTry // rethrow the error to the next step. Is this necessary, or will flatMap do that automatically?
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/configstate write row result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODESERVICES_CONFIGSTATE, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + n)
              if (n.asInstanceOf[Int] > 0) (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.services.updated", compositeId))) // there were no db errors, but determine if it actually found it or not
              else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            case Failure(t: AuthException) => t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== POST /orgs/{organization}/nodes/{node}/heartbeat ===============================
  @POST
  @Path("nodes/{node}/heartbeat")
  @Operation(summary = "Tells the exchange this node is still operating", description = "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def nodeHeartbeatRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "heartbeat") & post) { (orgid, id) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$id/heartbeat")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      complete({
        db.run(NodesTQ.getLastHeartbeat(compositeId).update(Some(ApiTime.nowUTC)).asTry).map({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"POST /orgs/$orgid/users/$id/heartbeat result: $v")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.updated")))
            } else {
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            }
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.updated", compositeId, t.toString))
          case Failure(t) => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.updated", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/nodes/{node}/errors ================================ */
  @GET
  @Path("nodes/{node}/errors")
  @Operation(summary = "Returns the node errors", description = "Returns any node errors. Can be run by any user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[NodeError])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/error")
  def nodeGetErrorsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "errors") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeErrorTQ.getNodeError(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/errors result size: "+list.size)
          if (list.nonEmpty) (HttpCode.OK, list.head.toNodeError)
          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/nodes/{node}/errors ===============================
  @PUT
  @Path("nodes/{node}/errors")
  @Operation(
    summary = "Adds/updates node error list",
    description = "Adds or updates any error of a node. This is called by the node or owning user.",
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
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "errors": [
   {
     "record_id": "string",
     "message": "string",
     "event_code": "string",
     "hidden": false
   }
  ]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeErrorRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/error")
  def nodePutErrorsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "errors") & put & entity(as[PutNodeErrorRequest])) { (orgid, id, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          db.run(reqBody.toNodeErrorRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/errors result: " + v)
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEERRORS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.errors.added")))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.errors.not.inserted", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.errors.not.inserted", compositeId, t.toString))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.errors.not.inserted", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.inserted", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/errors ===============================
  @DELETE
  @Path("nodes/{node}/errors")
  @Operation(summary = "Deletes the error list of a node", description = "Deletes the error list of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/error")
  def nodeDeleteErrorsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "errors") & delete) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeErrorTQ.getNodeError(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/errors result: " + v)
            if (v > 0) {
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEERRORS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => // there were no db errors, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.errors.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.errors.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // Look Here for node management status
  /* ====== GET /orgs/{organization}/nodes/{node}/status ================================ */
  @GET
  @Path("nodes/{node}/status")
  @Operation(summary = "Returns the node status", description = "Returns the node run time status, for example service container status. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "connectivity": {
    "string": true,
    "string": true
  },
  "services": [
    {
      "agreementId": "string",
      "serviceUrl": "string",
      "orgid": "string",
      "version": "string",
      "arch": "string",
      "containerStatus": [],
      "operatorStatus": {},
      "configState": "string"
    }
  ],
  "runningServices": "|orgid/serviceid|",
  "lastUpdated": "string"
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[NodeStatus])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/status")
  def nodeGetStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "status") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeStatusTQ.getNodeStatus(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/status result size: "+list.size)
          if (list.nonEmpty) (HttpCode.OK, list.head.toNodeStatus) //response body
          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/nodes/{node}/status ===============================
  @PUT
  @Path("nodes/{node}/status")
  @Operation(
    summary = "Adds/updates the node status",
    description = "Adds or updates the run time status of a node. This is called by the node or owning user.",
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
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "connectivity": {
    "string": true
  },
  "services": [
    {
      "agreementId": "78d7912aafb6c11b7a776f77d958519a6dc718b9bd3da36a1442ebb18fe9da30",
      "serviceUrl":"mydomain.com.location",
      "orgid":"ling.com",
      "version":"1.2",
      "arch":"amd64",
      "containerStatus": [
        {
          "name": "/dc23c045eb64e1637d027c4b0236512e89b2fddd3f06290c7b2354421d9d8e0d-location",
          "image": "summit.hovitos.engineering/x86/location:v1.2",
          "created": 1506086099,
          "state": "running"
        }
      ],
      "operatorStatus": {},
      "configState": "active"
    }
  ]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeStatusRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/status")
  def nodePutStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "status") & put & entity(as[PutNodeStatusRequest])) { (orgid, id, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          db.run(reqBody.toNodeStatusRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/status result: " + v)
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODESTATUS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("status.added.or.updated")))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.toString))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/status ===============================
  @DELETE
  @Path("nodes/{node}/status")
  @Operation(summary = "Deletes the status of a node", description = "Deletes the status of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/status")
  def nodeDeleteStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "status") & delete) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeStatusTQ.getNodeStatus(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/status result: " + v)
            if (v > 0) {
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODESTATUS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.status.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => // there were no db status, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.status.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.status.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/nodes/{node}/policy ================================ */
  @GET
  @Path("nodes/{node}/policy")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/policy")
  def nodeGetPolicyRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "policy") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodePolicyTQ.getNodePolicy(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/policy result size: "+list.size)
          if (list.nonEmpty) (HttpCode.OK, list.head.toNodePolicy)
          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/nodes/{node}/policy ===============================
  @PUT
  @Path("nodes/{node}/policy")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/policy")
  def nodePutPolicyRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "policy") & put & parameter("noheartbeat".?) & entity(as[PutNodePolicyRequest])) { (orgid, id, noheartbeat, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem(noheartbeat)) {
        complete({
          val noHB = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
          db.run(reqBody.toNodePolicyRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/policy result: " + v)
              NodesTQ.setLastUpdated(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastUpdated result: " + v)
              if (noHB) DBIO.successful(1).asTry  // skip updating lastHeartbeat
              else NodesTQ.setLastHeartbeat(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              if (!noHB) logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastHeartbeat result: " + n)
              try {
                val numUpdated: Int = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
                if (numUpdated > 0) {
                  resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEPOLICIES, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId))).asTry
                }
              } catch {
                case e: Exception => DBIO.failed(new DBProcessingError(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.updated", compositeId, e))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/policy updating resource status table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.policy.added.or.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.toString))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/policy ===============================
  @DELETE
  @Path("nodes/{node}/policy")
  @Operation(summary = "Deletes the policy of a node", description = "Deletes the policy of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/policy")
  def nodeDeletePolicyRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "policy") & delete) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodePolicyTQ.getNodePolicy(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/policy result: " + v)
            if (v > 0) {
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEPOLICIES, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource policy table: " + v)
            NodesTQ.setLastUpdated(compositeId, ApiTime.nowUTC).asTry
          case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
        })).map({
          case Success(v) => // there were no db policy, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " lastUpdated field updated: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/nodes/{node}/agreements ================================ */
  @GET
  @Path("nodes/{node}/agreements")
  @Operation(summary = "Returns all agreements this node is in", description = "Returns all agreements that this node is part of. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "agreements": {
    "agreementname": {
      "services": [
        {"orgid": "string",
         "url": "string"}
      ],
      "agrService": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "string"
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodeAgreementsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
  def nodeGetAgreementsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "agreements") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreements(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/agreements result size: ${list.size}")
          val agreements: Map[String, NodeAgreement] = list.map(e => e.agId -> e.toNodeAgreement).toMap
          val code: StatusCode = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/nodes/{node}/agreements/{agid} ================================ */
  @GET
  @Path("nodes/{node}/agreements/{agid}")
  @Operation(summary = "Returns an agreement for a node", description = "Returns the agreement with the specified agid for the specified node id. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
          examples = Array(
            new ExampleObject(
              value ="""{
  "agreements": {
    "agreementname": {
      "services": [
        {"orgid": "string",
         "url": "string"}
      ],
      "agrService": {
        "orgid": "string",
        "pattern": "string",
        "url": "string"
      },
      "state": "string",
      "lastUpdated": "string"
    }
  },
  "lastIndex": 0
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[GetNodeAgreementsResponse])
        ))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
  def nodeGetAgreementRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment) & get) { (orgid, id, agrId) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreement(compositeId, agrId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/agreements/$agrId result size: ${list.size}")
          val agreements: Map[String, NodeAgreement] = list.map(e => e.agId -> e.toNodeAgreement).toMap
          val code: StatusCode = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{organization}/nodes/{node}/agreements/{agid} ===============================
  @PUT
  @Path("nodes/{node}/agreements/{agid}")
  @Operation(
    summary = "Adds/updates an agreement of a node",
    description = "Adds a new agreement of a node, or updates an existing agreement. This is called by the node or owning user to give their information about the agreement.",
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
      new Parameter(
        name = "agid",
        in = ParameterIn.PATH,
        description = "ID of the agreement to be added/updated."
      ),
      new Parameter(name = "noheartbeat", in = ParameterIn.QUERY, required = false, description = "If set to 'true', skip the step to update the node's lastHeartbeat field.")
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              //name = "SomeExample",
              value = """{
  "services": [
    {
     "orgid": "myorg",
     "url": "mydomain.com.rtlsdr"
    }
  ],
  "agreementService": {
    "orgid": "myorg",
    "pattern": "myorg/mypattern",
    "url": "myorg/mydomain.com.sdr"
  },
  "state": "negotiating"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeAgreementRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
  def nodePutAgreementRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment) & put & parameter("noheartbeat".?) & entity(as[PutNodeAgreementRequest])) { (orgid, id, agrId, noheartbeat, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem(noheartbeat)) {
        complete({
          val noHB = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
          val maxAgreements: Int = ExchConfig.getInt("api.limits.maxAgreements")
          val getNumOwnedDbio = if (maxAgreements == 0) DBIO.successful(0) else NodeAgreementsTQ.getNumOwned(compositeId).result // avoid DB read for this if there is no max
          db.run(getNumOwnedDbio.flatMap({ xs =>
            if (maxAgreements != 0) logger.debug("PUT /orgs/"+orgid+"/nodes/"+id+"/agreements/"+agrId+" num owned: "+xs)
            val numOwned: Int = xs
            // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
            if (maxAgreements == 0 || numOwned <= maxAgreements) reqBody.toNodeAgreementRow(compositeId, agrId).upsert.asTry
            else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.agreements.for.node", maxAgreements) )).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/agreements/" + agrId + " result: " + v)
              NodesTQ.setLastUpdated(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastUpdated result: " + v)
              if (noHB) DBIO.successful(1).asTry  // skip updating lastHeartbeat
              else NodesTQ.setLastHeartbeat(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              if (!noHB) logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastHeartbeat result: " + v)
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/agreements/" + agrId + " updated in changes table: " + n)
              try {
                val numUpdated: Int = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
                if (numUpdated > 0) {
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreement.added.or.updated")))
                } else {
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
                }
              } catch {
                case e: Exception => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.updated", compositeId, e)))
              } // the specific exception is NumberFormatException
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreement.not.inserted.or.updated", agrId, compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.inserted.or.updated", agrId, compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/agreements ===============================
  @DELETE
  @Path("nodes/{node}/agreements")
  @Operation(summary = "Deletes all agreements of a node", description = "Deletes all of the current agreements of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
  def nodeDeleteAgreementsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "agreements") & delete) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodeAgreementsTQ.getAgreements(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /nodes/" + id + "/agreements result: " + v)
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.node.agreements.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements updated in changes table: " + v)
            NodesTQ.setLastUpdated(compositeId, ApiTime.nowUTC).asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements lastUpdated field updated: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreements.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreements.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreements.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/agreements/{agid} ===============================
  @DELETE
  @Path("nodes/{node}/agreements/{agid}")
  @Operation(summary = "Deletes an agreement of a node", description = "Deletes an agreement of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/agreement")
  def nodeDeleteAgreementRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment) & delete) { (orgid, id, agrId) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreement(compositeId,agrId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /nodes/" + id + "/agreements/" + agrId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.agreement.not.found", agrId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements/" + agrId + " updated in changes table: " + v)
            NodesTQ.setLastUpdated(compositeId, ApiTime.nowUTC).asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements/" + agrId + " lastUpdated field updated: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreement.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.agreement.not.deleted", agrId, compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.deleted", agrId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{organization}/nodes/{node}/msgs ===============================
  @POST
  @Path("nodes/{node}/msgs")
  @Operation(
    summary = "Sends a msg from an agbot to a node",
    description = "Sends a msg from an agbot to a node. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the node's public key). Can be run by any agbot.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "ID of the node to send a message to."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",
  "ttl": 86400
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodesMsgsRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
  def nodePostMsgRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "msgs") & post & entity(as[PostNodesMsgsRequest])) { (orgid, id, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.SEND_MSG_TO_NODE) { ident =>
      complete({
        val agbotId: String = ident.creds.id      //someday: handle the case where the acls allow users to send msgs
        var msgNum = ""
        val maxMessagesInMailbox: Int = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
        val getNumOwnedDbio = if (maxMessagesInMailbox == 0) DBIO.successful(0) else NodeMsgsTQ.getNumOwned(compositeId).result // avoid DB read for this if there is no max
        // Remove msgs whose TTL is past, then check the mailbox is not full, then get the agbot publicKey, then write the nodemsgs row, all in the same db.run thread
        db.run(getNumOwnedDbio.flatMap({ xs =>
          if (maxMessagesInMailbox != 0) logger.debug("POST /orgs/"+orgid+"/nodes/"+id+"/msgs mailbox size: "+xs)
          val mailboxSize: Int = xs
          if (maxMessagesInMailbox == 0 || mailboxSize < maxMessagesInMailbox) AgbotsTQ.getPublicKey(agbotId).result.asTry
          else DBIO.failed(new DBProcessingError(HttpCode.BAD_GW, ApiRespType.BAD_GW, ExchMsg.translate("node.mailbox.full", compositeId, maxMessagesInMailbox) )).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/msgs agbot publickey result: " + v)
            if (v.nonEmpty) { // it seems this returns success even when the agbot is not found
              val agbotPubKey: String = v.head
              if (agbotPubKey != "") NodeMsgRow(0, compositeId, agbotId, agbotPubKey, reqBody.message, ApiTime.nowUTC, ApiTime.futureUTC(reqBody.ttl)).insert.asTry
              else DBIO.failed(new DBProcessingError(HttpCode.BAD_INPUT, ApiRespType.BAD_INPUT, ExchMsg.translate("message.sender.public.key.not.in.exchange"))).asTry
            }
            else DBIO.failed(new DBProcessingError(HttpCode.BAD_INPUT, ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.agbot.not.found", agbotId))).asTry
          case Failure(t) => DBIO.failed(t).asTry // rethrow the error to the next step
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/msgs write row result: " + v)
            msgNum = v.toString
            resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, public = false, ResChangeResource.NODEMSGS, ResChangeOperation.CREATED).insert.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/msgs update changes table : " + v)
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.inserted", msgNum)))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (ExchangePosgtresErrorHandling.isKeyNotFoundError(t)) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.msg.nodeid.not.found", compositeId, t.getMessage)))
            else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.msg.not.inserted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.inserted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{organization}/nodes/{node}/msgs ================================ */
  @GET
  @Path("nodes/{node}/msgs")
  @Operation(summary = "Returns all msgs sent to this node", description = "Returns all msgs that have been sent to this node. They will be returned in the order they were sent. All msgs that have been sent to this node will be returned, unless the node has deleted some, or some are past their TTL. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "maxmsgs", in = ParameterIn.QUERY, required = false, description = "Maximum number of messages returned. If this is less than the number of messages available, the oldest messages are returned. Defaults to unlimited.")
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetNodeMsgsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
  def nodeGetMsgsRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "msgs") & get & parameter("maxmsgs".?)) { (orgid, id, maxmsgsStrOpt) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      validate(Try(maxmsgsStrOpt.map(_.toInt)).isSuccess, ExchMsg.translate("invalid.int.for.name", maxmsgsStrOpt.getOrElse(""), "maxmsgs")) {
        complete({
          // Set the query, including maxmsgs
          var maxIntOpt = maxmsgsStrOpt.map(_.toInt)
          var query = NodeMsgsTQ.getMsgs(compositeId).sortBy(_.msgId)
          if (maxIntOpt.getOrElse(0) > 0) query = query.take(maxIntOpt.get)
          // Get the msgs for this agbot
          db.run(query.result).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs result size: "+list.size)
            //logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs result: "+list.toString)
            val msgs: List[NodeMsg] = list.map(_.toNodeMsg).toList
            val code: StatusCode = if (msgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetNodeMsgsResponse(msgs, 0))
          })
        }) // end of complete
      }
    } // end of exchAuth
  }
  
  /* ====== GET /orgs/{organization}/nodes/{node}/msgs/{msgid} ================================ */
  @GET
  @Path("nodes/{node}/msgs/{msgid}")
  @Operation(description = "Returns A specific message that has been sent to this node. Deleted/post-TTL (Time To Live) messages will not be returned. Can be run by a user or the node.",
             parameters = Array(new Parameter(description = "ID of the node.",
                                              in = ParameterIn.PATH,
                                              name = "node",
                                              required = true),
                                new Parameter(description = "Specific node message.",
                                              in = ParameterIn.PATH,
                                              name = "msgid",
                                              required = true),
                                new Parameter(description = "Organization id.",
                                              in = ParameterIn.PATH,
                                              name = "organization",
                                              required = true)),
             responses = Array(new responses.ApiResponse(content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[GetNodeMsgsResponse]))),
                                                         description = "response body",
                                                         responseCode = "200"),
                               new responses.ApiResponse(description = "bad input",
                                                         responseCode = "400"),
                               new responses.ApiResponse(description = "invalid credentials",
                                                         responseCode = "401"),
                               new responses.ApiResponse(description = "access denied",
                                                         responseCode = "403"),
                               new responses.ApiResponse(description = "not found",
                                                         responseCode = "404")),
             summary = "Returns A specific message that has been sent to this node.")
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
  def nodeGetMsgRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "msgs" / Segment) & get) {
    (orgid, id, msgid) =>
      val compositeId: String = OrgAndId(orgid, id).toString
      
      exchAuth(TNode(compositeId),Access.READ) {
        _ =>
          complete({
            db.run(
              NodeMsgsTQ.getMsg(nodeId = compositeId,
                                msgId = msgid.toInt)
                        .result
                        .map(
                          result =>
                            GetNodeMsgsResponse(lastIndex = 0,
                                                messages = result.map(
                                                             message =>
                                                               NodeMsg(agbotId = message.agbotId,
                                                                       agbotPubKey = message.agbotPubKey,
                                                                       message = message.message,
                                                                       msgId = message.msgId,
                                                                       timeExpires = message.timeExpires,
                                                                       timeSent = message.timeSent)).toList))
                        .asTry
              )
              .map({
              case Success(message) =>
                if(message.messages.nonEmpty)
                  (HttpCode.OK, message)
                else
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
            })
          }) // end of complete
      } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/nodes/{node}/msgs/{msgid} ===============================
  @DELETE
  @Path("nodes/{node}/msgs/{msgid}")
  @Operation(summary = "Deletes a msg of an node", description = "Deletes a message that was sent to an node. This should be done by the node after each msg is read. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "msgid", in = ParameterIn.PATH, description = "ID of the msg to be deleted.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/message")
  def nodeDeleteMsgRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "msgs" / Segment) & delete) { (orgid, id, msgIdStr) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        try {
          val msgId: Int = msgIdStr.toInt   // this can throw an exception, that's why this whole section is in a try/catch
          db.run(NodeMsgsTQ.getMsg(compositeId,msgId).delete.asTry).map({
            case Success(v) =>
              logger.debug("DELETE /nodes/" + id + "/msgs/" + msgId + " updated in changes table: " + v)
              (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.msg.not.deleted", msgId, compositeId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.deleted", msgId, compositeId, t.toString)))
          })
        } catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
      }) // end of complete
    } // end of exchAuth
  }
  
  // ====== GET /orgs/{organization}/node-details ===================================
  @GET
  @Path("node-details")
  @Operation(description = "Returns all nodes with node errors, policy and status",
    summary = "Returns all nodes (edge devices) with node errors, policy and status. Can be run by any user or agbot.",
    parameters =
      Array(new Parameter(description = "Filter results to only include nodes with this architecture (can include % for wildcard - the URL encoding for % is %25)",
        in = ParameterIn.QUERY,
        name = "arch",
        required = false),
        new Parameter(description = "Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)",
          in = ParameterIn.QUERY,
          name = "node",
          required = false),
        new Parameter(description = "Filter results to only include nodes with this name (can include % for wildcard - the URL encoding for % is %25)",
          in = ParameterIn.QUERY,
          name = "name",
          required = false),
        new Parameter(description = "Filter results to only include nodes with this type ('device' or 'cluster')",
          in = ParameterIn.QUERY,
          name = "type",
          required = false),
        new Parameter(description = "Organization id",
          in = ParameterIn.PATH,
          name = "organization",
          required = true),
        new Parameter(description = "Filter results to only include nodes with this owner (can include % for wildcard - the URL encoding for % is %25)",
          in = ParameterIn.QUERY,
          name = "owner",
          required = false)),
    responses = Array(
      new responses.ApiResponse(description = "response body", responseCode = "200",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """[
  {
    "arch": "string",
    "connectivity": {
      "string": true,
      "string": false
    },
    "constraints": [
      "string",
      "string",
      "string"
    ],
    "errors": [
      {
        "event_code": "string",
        "hidden": true,
        "message": "string",
        "record_id": "string"
      }
    ],
    "heartbeatIntervals": {
      "intervalAdjustment": 0,
      "minInterval": 0,
      "maxInterval": 0
    },
    "id": "string",
    "lastHeartbeat": "string",
    "lastUpdatedNode": "string",
    "lastUpdatedNodeError": "string",
    "lastUpdatedNodePolicy": "string",
    "lastUpdatedNodeStatus": "string",
    "msgEndPoint": "",
    "name": "string",
    "nodeType": "device",
    "owner": "string",
    "orgid": "string",
    "pattern": "",
    "properties": [
      {"string": "string"},
      {"string": "string"},
      {"string": "string"}
    ],
    "publicKey": "string",
    "registeredServices": [
      {
        "configState": "active",
        "numAgreements": 0,
        "policy": "",
        "properties": [],
        "url": "string"
      },
      {
        "configState": "active",
        "numAgreements": 0,
        "policy": "",
        "properties": [],
        "url": "string"
      },
      {
        "configState": "active",
        "numAgreements": 0,
        "policy": "",
        "properties": [],
        "url": "string"
      }
    ],
    "runningServices": "|orgid/serviceid|",
    "services": [
      {
        "agreementId": "string",
        "arch": "string",
        "containerStatus": [],
        "operatorStatus": {},
        "orgid": "string",
        "serviceUrl": "string",
        "version": "string",
        "configState": "string"
      }
    ],
    "softwareVersions": {},
    "token": "string",
    "userInput": [
      {
        "inputs": [
          {
            "name": "var1",
            "value": "someString"
          },
          {
            "name": "var2",
            "value": 5
          },
          {
            "name": "var3",
            "value": 22.2
          }
        ],
        "serviceArch": "string",
        "serviceOrgid": "string",
        "serviceUrl": "string",
        "serviceVersionRange": "string",
        "ha_group": "string"
      }
    ]
  }
]"""
              )
            ),
            mediaType = "application/json",
            array = new ArraySchema(schema = new Schema(implementation = classOf[NodeDetails]))
          )
        )),
      new responses.ApiResponse(description = "invalid credentials", responseCode = "401"),
      new responses.ApiResponse(description = "access denied", responseCode = "403"),
      new responses.ApiResponse(description = "not found", responseCode = "404")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def nodesGetDetails: Route =
    (path("orgs" / Segment / "node-details") & get & parameter("arch".?, "id".?, "name".?, "type".?, "owner".?)) {
      (orgid: String, arch: Option[String], id: Option[String], name: Option[String], nodeType: Option[String], owner: Option[String]) =>
        exchAuth(TNode(OrgAndId(orgid,"#").toString), Access.READ) {
          ident =>
            validateWithMsg(GetNodesUtils.getNodesProblem(nodeType)) {
              complete({
                implicit val jsonFormats: Formats = DefaultFormats
                val ownerFilter: Option[String] =
                  if(ident.isAdmin ||
                     ident.isSuperUser ||
                     ident.role.equals(AuthRoles.Agbot))
                    owner
                  else
                    Some(ident.identityString)
                
                val getNodes =
                  for {
                    nodes <- NodesTQ
                                   .filterOpt(arch)((node, arch) => node.arch like arch)
                                   .filterOpt(id)((node, id) => node.id like id)
                                   .filterOpt(name)((node, name) => node.name like name)
                                   .filterOpt(nodeType)(
                                     (node, nodeType) => {
                                       node.nodeType === nodeType.toLowerCase ||
                                        node.nodeType === nodeType.toLowerCase.replace("device", "")
                                     }) // "" === ""
                                   .filter(_.orgid === orgid)
                                   .filterOpt(ownerFilter)((node, ownerFilter) => node.owner like ownerFilter)
                                   .joinLeft(NodeErrorTQ.filterOpt(id)((nodeErrors, id) => nodeErrors.nodeId like id)) // (Node, NodeError)
                                     .on(_.id === _.nodeId)
                                   .joinLeft(NodePolicyTQ.filterOpt(id)((nodePolicy, id) => nodePolicy.nodeId like id)) // ((Node, NodeError), NodePolicy)
                                     .on(_._1.id === _.nodeId)
                                   .joinLeft(NodeStatusTQ.filterOpt(id)((nodeStatuses, id) => nodeStatuses.nodeId like id)) // (((Nodes, Node Errors), Node Policy), Node Statuses)
                                     .on(_._1._1.id === _.nodeId) // node.id === nodeStatus.nodeid
                                   .joinLeft(NodeGroupTQ.join(NodeGroupAssignmentTQ.filterOpt(id)((assignment, id) => assignment.node like id)).on(_.group === _.group)) // ((((Nodes, Node Errors), Node Policy), Node Statuses), (Node Group, Node Group Assignment)))
                                     .on(_._1._1._1.id === _._2.node) // node.id === nodeGroupAssignment.node
                                   .sortBy(_._1._1._1._1.id.asc)     // node.id ASC
                                   // ((((Nodes, Node Errors), Node Policy), Node Statuses), (Node Group, Node Group Assignment)))
                                   // Flatten the tupled structure, lexically sort columns.
                                   .map(
                                     node =>
                                       (node._1._1._1._1.arch,
                                         node._1._1._1._1.id,
                                         node._1._1._1._1.heartbeatIntervals,
                                         node._1._1._1._1.lastHeartbeat,
                                         node._1._1._1._1.lastUpdated,
                                         node._1._1._1._1.msgEndPoint,
                                         node._1._1._1._1.name,
                                         node._1._1._1._1.nodeType,
                                         node._1._1._1._1.orgid,
                                         node._1._1._1._1.owner,
                                         node._1._1._1._1.pattern,
                                         node._1._1._1._1.publicKey,
                                         node._1._1._1._1.regServices,
                                         node._1._1._1._1.softwareVersions,
                                         (if(ident.isAdmin ||
                                             ident.isSuperUser) // Do not pull nor query the Node's token if (Super)Admin.
                                           node._1._1._1._1.id.substring(0,0) // node.id -> ""
                                         else
                                           node._1._1._1._1.token),
                                         node._1._1._1._1.userInput,
                                         node._1._1._1._1.clusterNamespace,
                                         node._1._1._1._1.isNamespaceScoped,
                                         node._1._1._1._2,           // Node Errors (errors, lastUpdated)
                                         node._1._1._2,              // Node Policy (constraints, lastUpdated, properties)
                                         node._1._2,                 // Node Statuses (connectivity, lastUpdated, runningServices, services)
                                         node._2.map(_._1.name)))    // Node Group
                                   .result
                                   // Complete type conversion to something more usable.
                                   .map(
                                     results =>
                                       results.map(
                                         node =>
                                           NodeDetails(arch =
                                             if(node._1.isEmpty)
                                               None
                                             else
                                               Option(node._1),
                                             connectivity =
                                               if(node._21.isEmpty ||
                                                  node._21.get.connectivity.isEmpty)
                                                 None
                                               else
                                                 Option(read[Map[String, Boolean]](node._21.get.connectivity)),
                                             constraints =
                                              if(node._20.isEmpty ||
                                                 node._20.get.constraints.isEmpty)
                                                None
                                              else
                                                Option(read[List[String]](node._20.get.constraints)),
                                             errors =
                                               if(node._19.isEmpty ||
                                                  node._19.get.errors.isEmpty)
                                                 None
                                               else
                                                 Option(read[List[Any]](node._19.get.errors)),
                                             id = node._2,
                                             heartbeatIntervals =
                                               if(node._3.isEmpty)
                                                 Option(NodeHeartbeatIntervals(0, 0, 0))
                                               else
                                                 Option(read[NodeHeartbeatIntervals](node._3)),
                                             lastHeartbeat = node._4,
                                             lastUpdatedNode = node._5,
                                             lastUpdatedNodeError =
                                               if(node._19.isDefined)
                                                 Option(node._19.get.lastUpdated)
                                               else
                                                 None,
                                             lastUpdatedNodePolicy =
                                              if(node._20.isDefined)
                                                Option(node._20.get.lastUpdated)
                                              else
                                                None,
                                             lastUpdatedNodeStatus =
                                               if(node._21.isDefined)
                                                 Option(node._21.get.lastUpdated)
                                               else
                                                 None,
                                             msgEndPoint =
                                               if(node._6.isEmpty)
                                                 None
                                               else
                                                 Option(node._6),
                                             name =
                                               if(node._7.isEmpty)
                                                 None
                                               else
                                                 Option(node._7),
                                             nodeType =
                                               if(node._8.isEmpty)
                                                 "device"
                                               else
                                                 node._8,
                                             orgid = node._9,
                                             owner = node._10,
                                             pattern =
                                               if(node._11.isEmpty)
                                                 None
                                               else
                                                 Option(node._11),
                                             properties =
                                              if(node._20.isEmpty ||
                                                 node._20.get.properties.isEmpty)
                                                None
                                              else
                                                Option(read[List[OneProperty]](node._20.get.properties)),
                                             publicKey =
                                               if(node._12.isEmpty)
                                                 None
                                               else
                                                 Option(node._12),
                                             registeredServices =
                                               if(node._13.isEmpty)
                                                 None
                                               else
                                                 Option(read[List[RegService]](node._13).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties, rs.version))),
                                             runningServices =
                                               if(node._21.isEmpty ||
                                                  node._21.get.services.isEmpty)
                                                 None
                                               else
                                                 Option(node._21.get.runningServices),
                                             services =
                                               if(node._21.isEmpty ||
                                                  node._21.get.services.isEmpty)
                                                 None
                                               else
                                                 Option(read[List[OneService]](node._21.get.services)),
                                             softwareVersions =
                                               if(node._14.isEmpty)
                                                 None
                                               else
                                                 Option(read[Map[String, String]](node._14)),
                                             token =
                                               if(node._15.isEmpty)
                                                 StrConstants.hiddenPw
                                               else
                                                 node._15,
                                             userInput =
                                               if(node._16.isEmpty)
                                                 None
                                               else
                                                 Option(read[List[OneUserInputService]](node._16)),
                                             ha_group = node._22,
                                             clusterNamespace = node._17,
                                             isNamespaceScoped = node._18)).toList)
                  } yield(nodes)
                
                db.run(getNodes.asTry).map({
                  case Success(nodes) =>
                    if(nodes.nonEmpty)
                      (HttpCode.OK, nodes)
                    else
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
                })
              }) // end of complete
            }
        } // end of exchAuth
    }

  /* ====== GET /orgs/{organization}/nodes/{node}/managementStatus ================================ */
  @GET
  @Path("nodes/{node}/managementStatus")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
  def nodeGetAllMgmtPolStatus: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { ident =>
      complete({
        var q = NodeMgmtPolStatuses.getNodeMgmtPolStatuses(orgid + "/" + id).sortBy(_.policy.asc.nullsFirst)
        db.run(q.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/managementStatus result size: "+list.size)
          val code: StatusCode =
            if(list.nonEmpty)
              StatusCodes.OK
            else
              StatusCodes.NotFound
          (code, GetNMPStatusResponse(list))
        })
      }) // end of complete
    } // end of validate
  } // end of exchAuth
  
  /* ====== GET /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ================================ */
  @GET
  @Path("nodes/{node}/managementStatus/{mgmtpolicy}")
  @Operation(summary = "Returns status for nodeid", description = "Returns the management status of the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned"),
      new Parameter(name = "mgmtpolicy", in = ParameterIn.PATH, description = "ID of the node management policy.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
      "managementStatus": {
        "mgmtpolicy": {
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
  def nodeGetMgmtPolStatus: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) & get) { (orgid, id, mgmtpolicy) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes/$id/managementStatus/$mgmtpolicy")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(compositeId, orgid + "/" + mgmtpolicy).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/managementStatus/$mgmtpolicy status result size: ${list.size}")
          val code: StatusCode =
            if(list.nonEmpty)
              StatusCodes.OK
            else
              StatusCodes.NotFound
          (code, GetNMPStatusResponse(list))
        })
      }) // end of complete
    } // end of validate
  } // end of exchAuth
  
  // =========== PUT /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ===============================
  @PUT
  @Path("nodes/{node}/managementStatus/{mgmtpolicy}")
  @Operation(
    summary = "Adds/updates the status of the Management Policy running on the Node.",
    description = "Adds or updates the run time status of a Management Policy running on a Node. This is called by the Agreement Bot.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization identifier"
      ),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "Node identifier"
      ),
      new Parameter(
        name = "mgmtpolicy",
        in = ParameterIn.PATH,
        description = "Management Policy identifier"
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
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
    "errorMessage": "Upgrade process failed"
  }
}
                      """
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodeMgmtPolStatusRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
  def nodePutMgmtPolStatus: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) & put & entity(as[PutNodeMgmtPolStatusRequest])) { (orgid, id, mgmtpolicy, reqBody) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      //validateWithMsg(reqBody.getAnyProblem) {
        complete({
          db.run(
            NodeMgmtPolStatuses
              .insertOrUpdate(
                NodeMgmtPolStatusRow(actualStartTime = reqBody.agentUpgradePolicyStatus.startTime,
                                     certificateVersion =
                                       if(reqBody.agentUpgradePolicyStatus.upgradedVersions.isDefined)
                                         reqBody.agentUpgradePolicyStatus.upgradedVersions.get.certVersion
                                       else
                                         None,
                                     configurationVersion =
                                       if(reqBody.agentUpgradePolicyStatus.upgradedVersions.isDefined)
                                         reqBody.agentUpgradePolicyStatus.upgradedVersions.get.configVersion
                                       else
                                         None,
                                     endTime = reqBody.agentUpgradePolicyStatus.endTime,
                                     errorMessage = reqBody.agentUpgradePolicyStatus.errorMessage,
                                     node = compositeId,
                                     policy = s"$orgid/$mgmtpolicy",
                                     scheduledStartTime = reqBody.agentUpgradePolicyStatus.scheduledTime,
                                     softwareVersion =
                                       if(reqBody.agentUpgradePolicyStatus.upgradedVersions.isDefined)
                                         reqBody.agentUpgradePolicyStatus.upgradedVersions.get.softwareVersion
                                       else
                                         None,
                                     status = reqBody.agentUpgradePolicyStatus.status,
                                     updated = ApiTime.nowUTC)
              )
              .andThen(
                resourcechange.ResourceChange(category = ResChangeCategory.NODE,
                               changeId = 0L,
                               id = id,
                               operation = ResChangeOperation.CREATEDMODIFIED,
                               orgId = orgid,
                               public = false,
                               resource = ResChangeResource.NODEMGMTPOLSTATUS)
                              .insert)
              .transactionally.asTry.map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/managementPolicy/" + mgmtpolicy + " result: " + v)
                  logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/managementPolicy/" + mgmtpolicy + " updating resource status table: " + v)
                  
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.managementpolicy.status.added.or.updated", orgid + "/" + mgmtpolicy, compositeId)))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                    (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", orgid + "/" + mgmtpolicy, compositeId, t.getMessage)))
                  else
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", orgid + "/" + mgmtpolicy, compositeId, t.toString))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:"))
                    (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", orgid + "/" + mgmtpolicy, compositeId, t.getMessage)))
                  else
                    (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", orgid + "/" + mgmtpolicy, compositeId, t.toString)))
              })
          )
        }) // end of complete
      // } // end of validateWithMsg
    } // end of exchAuth
  }
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ===============================
  @DELETE
  @Path("nodes/{node}/managementStatus/{mgmtpolicy}")
  @Operation(
    summary = "Deletes the status of the Management Policy running on the Node",
    description = "Deletes the run time status of a Management Policy running on a Node. This is called by the Agreement Bot.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization identifier."),
      new Parameter(
        name = "node",
        in = ParameterIn.PATH,
        description = "Node identifier"),
      new Parameter(
        name = "mgmtpolicy",
        in = ParameterIn.PATH,
        description = "Management Policy identifier")),
  responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
  def nodeDeleteMgmtPolStatus: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) & delete) { (orgid, id, mgmtpolicy) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/nodes/$id/managementStatus/$mgmtpolicy")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(compositeId, orgid + "/" + mgmtpolicy).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if(v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/nodes/$id/managementStatus/$mgmtpolicy result: $v")
              resourcechange.ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEMGMTPOLSTATUS, ResChangeOperation.DELETED).insert.asTry
            }
            else
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", orgid + "/" + mgmtpolicy, compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug(s"DELETE /orgs/$orgid/nodes/$id/managementStatus/$mgmtpolicy updated in changes table: $v")
            
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.managementpolicy.status.deleted", orgid + "/" + mgmtpolicy, compositeId)))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            if(t.getMessage.contains("couldn't find NODEMGMTPOLSTATUS")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", orgid + "/" + mgmtpolicy, compositeId)))
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.managementpolicy.status.not.deleted", orgid + "/" + mgmtpolicy, compositeId, t.toString))
          case Failure(t) =>
            if(t.getMessage.contains("couldn't find NODEMGMTPOLSTATUS"))
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", orgid + "/" + mgmtpolicy, compositeId)))
            else
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.managementpolicy.status.not.deleted", orgid + "/" + mgmtpolicy, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
}
