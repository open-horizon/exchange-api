package org.openhorizon.exchangeapi.route.node

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ArraySchema, Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, parameter, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.deploymentpattern.OneUserInputService
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodesTQ, OneService, RegService}
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.NodePolicyTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.node.status.NodeStatusTQ
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode, StrConstants}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}")
trait Details extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
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
  def getDetails(@Parameter(hidden = true) identity: Identity,
                 @Parameter(hidden = true) organization: String): Route =
    parameter("arch".?,
              "id".?,
              "name".?,
              "type".?,
              "owner".?) {
      (arch: Option[String],
       id: Option[String],
       name: Option[String],
       nodeType: Option[String],
       owner: Option[String]) =>
        validateWithMsg(GetNodesUtils.getNodesProblem(nodeType)) {
          complete({
            implicit val jsonFormats: Formats = DefaultFormats
            val ownerFilter: Option[String] =
              if(identity.isAdmin ||
                 identity.isSuperUser ||
                 identity.role.equals(AuthRoles.Agbot))
                owner
              else
                Some(identity.identityString)
            
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
                                .filter(_.orgid === organization)
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
                                      (if(identity.isAdmin ||
                                        identity.isSuperUser) // Do not pull nor query the Node's token if (Super)Admin.
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
    }
    
  val details: Route =
    path("orgs" / Segment / "node-details") {
      organization =>
        get {
          exchAuth(TNode(OrgAndId(organization,"#").toString), Access.READ) {
            identity =>
              getDetails(identity, organization)
          }
        }
    }
}
