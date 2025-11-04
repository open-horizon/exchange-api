package org.openhorizon.exchangeapi.route.node.managementpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, path, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.ExchangeApiApp
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, DBProcessingError, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.route.node.PutNodeMgmtPolStatusRequest
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.{GetNMPStatusResponse, NodeMgmtPolStatusRow, NodeMgmtPolStatuses}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling}
import scalacache.modes.scalaFuture.mode
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "node/management policy")
trait Status extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ===============================
  @DELETE
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
  def deleteStatusMangementPolicy(@Parameter(hidden = true) identity: Identity2,
                                  @Parameter(hidden = true) managementPolicy: String,
                                  @Parameter(hidden = true) node: String,
                                  @Parameter(hidden = true) organization: String,
                                  @Parameter(hidden = true) resource: String): Route =
    delete {
      logger.debug(s"DELETE /orgs/$organization/nodes/$node/managementStatus/$managementPolicy - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(resource, organization + "/" + managementPolicy).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if(v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$organization/nodes/$node/managementStatus/$managementPolicy result: $v")
              ResourceChange(0L, organization, node, ResChangeCategory.NODE, false, ResChangeResource.NODEMGMTPOLSTATUS, ResChangeOperation.DELETED).insert.asTry
            }
            else
              DBIO.failed(new DBProcessingError(StatusCodes.NotFound, ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", organization + "/" + managementPolicy, resource))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug(s"DELETE /orgs/$organization/nodes/$node/managementStatus/$managementPolicy updated in changes table: $v")
            
            (StatusCodes.NoContent, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.managementpolicy.status.deleted", organization + "/" + managementPolicy, resource)))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            if(t.getMessage.contains("couldn't find NODEMGMTPOLSTATUS")) (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", organization + "/" + managementPolicy, resource)))
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.managementpolicy.status.not.deleted", organization + "/" + managementPolicy, resource, t.toString))
          case Failure(t) =>
            if(t.getMessage.contains("couldn't find NODEMGMTPOLSTATUS"))
              (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.managementpolicy.status.not.found", organization + "/" + managementPolicy, resource)))
            else
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.managementpolicy.status.not.deleted", organization + "/" + managementPolicy, resource, t.toString)))
        })
      })
    }
  
   /* ====== GET /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ================================ */
  @GET
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
  def getStatusMangementPolicy(@Parameter(hidden = true) identity: Identity2,
                               @Parameter(hidden = true) managementPolicy: String,
                               @Parameter(hidden = true) node: String,
                               @Parameter(hidden = true) organization: String,
                               @Parameter(hidden = true) resource: String): Route =
    {
      logger.debug(s"GET /orgs/$organization/nodes/$node/managementStatus/$managementPolicy - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      complete({
        db.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(resource, organization + "/" + managementPolicy).result).map({ list =>
          logger.debug(s"GET /orgs/$organization/nodes/$node/managementStatus/$managementPolicy status result size: ${list.size}")
          val code: StatusCode =
            if(list.nonEmpty)
              StatusCodes.OK
            else
              StatusCodes.NotFound
          (code, GetNMPStatusResponse(list))
        })
      })
    }
  
  // =========== PUT /orgs/{organization}/nodes/{node}/managementStatus/{mgmtpolicy} ===============================
  @PUT
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
  def putStatusManagementPolicy(@Parameter(hidden = true) identity: Identity2,
                                @Parameter(hidden = true) managementPolicy: String,
                                @Parameter(hidden = true) node: String,
                                @Parameter(hidden = true) organization: String,
                                @Parameter(hidden = true) resource: String): Route =
    put {
      entity(as[PutNodeMgmtPolStatusRequest]) {
        reqBody =>
          logger.debug(s"PUT /orgs/${organization}/nodes/${node}/managementStatus/${managementPolicy} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
          
          val INSTANT: Instant = Instant.now()
          
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
                                       node = resource,
                                       policy = s"$organization/$managementPolicy",
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
                  ResourceChange(category = ResChangeCategory.NODE,
                                 changeId = 0L,
                                 id = node,
                                 lastUpdated = INSTANT,
                                 operation = ResChangeOperation.CREATEDMODIFIED,
                                 orgId = organization,
                                 public = false,
                                 resource = ResChangeResource.NODEMGMTPOLSTATUS)
                                .insert)
                .transactionally.asTry.map({
                  case Success(v) =>
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/managementPolicy/" + managementPolicy + " result: " + v)
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + "/managementPolicy/" + managementPolicy + " updating resource status table: " + v)
                    
                    (StatusCodes.Created, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.managementpolicy.status.added.or.updated", organization + "/" + managementPolicy, resource)))
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    if (ExchangePosgtresErrorHandling.isAccessDeniedError(t))
                      (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", organization + "/" + managementPolicy, resource, t.getMessage)))
                    else
                      ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", organization + "/" + managementPolicy, resource, t.toString))
                  case Failure(t) =>
                    if (t.getMessage.startsWith("Access Denied:"))
                      (StatusCodes.Forbidden, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", organization + "/" + managementPolicy, resource, t.getMessage)))
                    else
                      (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.managementpolicy.status.not.inserted.or.updated", organization + "/" + managementPolicy, resource, t.toString)))
                })
            )
          })
      }
    }
  
  def statusManagementPolicy(identity: Identity2): Route =
    path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) {
      (organization,
       node,
       managementPolicy) =>
        val resource: String = OrgAndId(organization, node).toString
        val resource_type: String = "node"
        val cacheCallback: Future[(UUID, Boolean)] =
          cacheResourceOwnership.cachingF(organization, node, resource_type)(ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) {
            ExchangeApiApp.getOwnerOfResource(organization = organization, resource = resource, resource_type = resource_type)
          }
        
        def routeMethods(owningResourceIdentity: Option[UUID] = None): Route =
          (delete | put) {
            exchAuth(TNode(resource, owningResourceIdentity), Access.WRITE, validIdentity = identity) {
              _ =>
                deleteStatusMangementPolicy(identity, managementPolicy, node, organization, resource) ~
                putStatusManagementPolicy(identity, managementPolicy, node, organization, resource)
            }
          } ~
          get {
            exchAuth(TNode(resource, owningResourceIdentity),Access.READ, validIdentity = identity) {
              _ =>
                getStatusMangementPolicy(identity, managementPolicy, node, organization, resource)
            }
          }
        
        onComplete(cacheCallback) {
          case Failure(_) => routeMethods()
          case Success((owningResourceIdentity, _)) => routeMethods(owningResourceIdentity = Option(owningResourceIdentity))
        }
    }
}
