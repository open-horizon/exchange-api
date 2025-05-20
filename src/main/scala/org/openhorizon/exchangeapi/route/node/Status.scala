package org.openhorizon.exchangeapi.route.node

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.status.{NodeStatus, NodeStatusTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/nodes/{node}/status")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/status")
trait Status extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // Look Here for node management status
  /* ====== GET /orgs/{organization}/nodes/{node}/status ================================ */
  @GET
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
  def getStatusNode(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) node: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route = {
    logger.debug(s"GET /orgs/${organization}/nodes/${node}/status - By ${identity.resource}:${identity.role}")
    complete({
      db.run(NodeStatusTQ.getNodeStatus(resource).result).map({
        list =>
          logger.debug("GET /orgs/"+organization+"/nodes/"+node+"/status result size: "+list.size)
          
          if(list.nonEmpty)
            (HttpCode.OK, list.head.toNodeStatus) //response body
          else
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
      })
    })
  }
  
  // =========== PUT /orgs/{organization}/nodes/{node}/status ===============================
  @PUT
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
  def putStatusNode(@Parameter(hidden = true) identity: Identity2,
                    @Parameter(hidden = true) node: String,
                    @Parameter(hidden = true) organization: String,
                    @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PutNodeStatusRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/${organization}/nodes/${node}/status - By ${identity.resource}:${identity.role}")
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              db.run(reqBody.toNodeStatusRow(resource).upsert.asTry.flatMap({
                case Success(v) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/status result: " + v)
                  ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODESTATUS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource status table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("status.added.or.updated")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.status.not.inserted.or.updated", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.status.not.inserted.or.updated", resource, t.toString))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.status.not.inserted.or.updated", resource, t.getMessage)))
                  else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.inserted.or.updated", resource, t.toString)))
              })
            })
          }
      }
    }
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/status ===============================
  @DELETE
  @Operation(summary = "Deletes the status of a node", description = "Deletes the status of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteStatusNode(@Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) node: String,
                       @Parameter(hidden = true) organization: String,
                       @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/${organization}/nodes/${node}/status - By ${identity.resource}:${identity.role}")
      complete({
        db.run(NodeStatusTQ.getNodeStatus(resource).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/nodes/" + node + "/status result: " + v)
            if (v > 0) {
              ResourceChange(0L, organization, node, ResChangeCategory.NODE, false, ResChangeResource.NODESTATUS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.status.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => // there were no db status, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource status table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.status.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.status.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.deleted", resource, t.toString)))
        })
      })
    }
  
  def statusNode(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "status") {
      (organization, node) =>
        val resource: String = OrgAndId(organization, node).toString
        
        (delete | put) {
          exchAuth(TNode(resource), Access.WRITE, validIdentity = identity) {
            _ =>
              deleteStatusNode(identity, node, organization, resource) ~
              putStatusNode(identity, node, organization, resource)
          }
        } ~
        get{
          exchAuth(TNode(resource),Access.READ, validIdentity = identity) {
            _ =>
              getStatusNode(identity, node, organization, resource)
          }
        }
    }
}
