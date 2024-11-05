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
import org.json4s.JValue
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.PutNodeErrorRequest
import org.openhorizon.exchangeapi.route.search.NodeError
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

@Path("/v1/orgs/{organization}/nodes/{node}/errors")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/error")
trait Errors extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/errors ===============================
  @DELETE
  @Operation(summary = "Deletes the error list of a node", description = "Deletes the error list of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "node", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteErrors(@Parameter(hidden = true) node: String,
                   @Parameter(hidden = true) organization: String,
                   @Parameter(hidden = true) resource: String): Route =
    delete {
      complete({
        db.run(NodeErrorTQ.getNodeError(resource).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/nodes/" + node + "/errors result: " + v)
            if (v > 0) {
              ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODEERRORS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => // there were no db errors, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource status table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.errors.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.errors.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/nodes/{node}/errors ================================ */
  @GET
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
  def getErrors(@Parameter(hidden = true) node: String,
                @Parameter(hidden = true) organization: String,
                @Parameter(hidden = true) resource: String): Route =
    complete({
      db.run(NodeErrorTQ.getNodeError(resource).result).map({
        list =>
          logger.debug("GET /orgs/"+organization+"/nodes/"+node+"/errors result size: "+list.size)
          if(list.nonEmpty)
            (HttpCode.OK, list.head.toNodeError)
          else
            (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
      })
    })
  
  // =========== PUT /orgs/{organization}/nodes/{node}/errors ===============================
  @PUT
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
          schema = new Schema(implementation = classOf[List[String]])
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
  def putErrors(@Parameter(hidden = true) node: String,
                @Parameter(hidden = true) organization: String,
                @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PutNodeErrorRequest]) {
        reqBody =>
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              db.run(reqBody.toNodeErrorRow(resource).upsert.asTry.flatMap({
                case Success(v) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/errors result: " + v)
                  ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODEERRORS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource status table: " + v)
                  (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.errors.added")))
                case Failure(t: org.postgresql.util.PSQLException) =>
                  if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.errors.not.inserted", resource, t.getMessage)))
                  else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.errors.not.inserted", resource, t.toString))
                case Failure(t) =>
                  if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.errors.not.inserted", resource, t.getMessage)))
                  else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.inserted", resource, t.toString)))
              })
            })
          }
      }
    }
  
  val errors: Route =
    path("orgs" / Segment / "nodes" / Segment / "errors") {
      (organization,
       node) =>
        val resource: String = OrgAndId(organization, node).toString
        
        (delete | put) {
          exchAuth(TNode(resource), Access.WRITE) {
            _ =>
              deleteErrors(node, organization, resource) ~
              putErrors(node, organization, resource)
          }
        } ~
        get {
          exchAuth(TNode(resource),Access.READ) {
            _ =>
              getErrors(node, organization, resource)
          }
        }
    }
}
