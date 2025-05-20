package org.openhorizon.exchangeapi.route.node

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.PathMatchers.Segment
import org.apache.pekko.http.scaladsl.server.Route
import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import jakarta.ws.rs.{GET, Path}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import org.openhorizon.exchangeapi.auth.{Access, AuthRoles, AuthenticationSupport, Identity2, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.{Node, NodeHeartbeatIntervals, NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ExchMsg, StrConstants}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.Rep

import scala.concurrent.ExecutionContext

@Path("/v1/orgs/{organization}/nodes")
trait Nodes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{organization}/nodes ================================
  @GET
  @Operation(summary = "Returns all nodes", description = "Returns all nodes (edge devices). Can be run by any user or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "name", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this name (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "nodetype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this nodeType ('device' or 'cluster')"),
      new Parameter(name = "arch", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this arch (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "nodes": {
    "orgid/nodeid": {
      "token": "string",
      "name": "string",
      "owner": "string",
      "nodeType": "device",
      "pattern": "",
      "registeredServices": [
        {
          "url": "string",
          "numAgreements": 0,
          "configState": "active",
          "policy": "",
          "properties": [],
          "version": ""
        },
        {
          "url": "string",
          "numAgreements": 0,
          "configState": "active",
          "policy": "",
          "properties": [],
          "version": ""
        },
        {
          "url": "string",
          "numAgreements": 0,
          "configState": "active",
          "policy": "",
          "properties": [],
          "version": ""
        }
      ],
      "userInput": [
        {
          "serviceOrgid": "string",
          "serviceUrl": "string",
          "serviceArch": "string",
          "serviceVersionRange": "string",
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
          ]
        }
      ],
      "msgEndPoint": "",
      "softwareVersions": {},
      "lastHeartbeat": "string",
      "publicKey": "string",
      "arch": "string",
      "heartbeatIntervals": {
        "minInterval": 0,
        "maxInterval": 0,
        "intervalAdjustment": 0
      },
      "ha_group": "groupName",
      "lastUpdated": "string",
      "clusterNamespace": "MyNamespace",
      "isNamespaceScoped": false
    }
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def getNodes(@Parameter(hidden = true) identity: Identity2,
               @Parameter(hidden = true) organization: String): Route =
    get {
      parameter("idfilter".?,
                "name".?,
                "owner".?,
                "arch".?,
                "nodetype".?,
                "clusternamespace".?,
                "isNamespaceScoped".as[Boolean].?) {
        (idfilter, name, owner, arch, nodetype, clusterNamespace, isNamespaceScoped) =>
          logger.debug(s"Doing GET /orgs/$organization/nodes - By ${identity.resource}:${identity.role}")
          
              validateWithMsg(if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase))
                                Option(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
                              else
                                None) {
                complete({
                  logger.debug(s"GET /orgs/$organization/nodes - By ${identity.resource}:${identity.role}") // can't display the whole ident object, because that contains the pw/token
                  implicit val jsonFormats: Formats = DefaultFormats
                  
                  val nodes =
                    NodesTQ.filter(_.orgid === organization)
                      .filterOpt(arch)((node, arch) => node.arch like arch)
                      .filterOpt(clusterNamespace)((node, clusterNamespace) => node.clusterNamespace like clusterNamespace)
                      .filterOpt(idfilter)((node, id) => node.id like id)
                      .filterOpt(isNamespaceScoped)((node, isNamespaceScoped) => node.isNamespaceScoped === isNamespaceScoped)
                      .filterOpt(name)((node, name) => node.name like name)
                      .filterIf(nodetype.isDefined && "cluster" == nodetype.get.toLowerCase())(_.nodeType === "cluster")
                      .filterIf(nodetype.isDefined && "device|".r.matches(nodetype.get.toLowerCase()))(_.nodeType inSet Set("", "device"))
                      
                      .filterIf(identity.isStandardUser)(_.owner === identity.identifier)
                      .filterIf(identity.isOrgAdmin || (identity.isAgbot && !identity.isMultiTenantAgbot))(_.orgid === identity.organization)
                      .filterIf(identity.isNode)(_.id === identity.resource)
                      .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                      .on(_.owner === _._2)
                      .filterIf(owner.isDefined && (identity.isOrgAdmin || identity.role.equals(AuthRoles.Agbot)))(nodes => ((nodes._2._1 ++ "/" ++ nodes._2._3) like owner.getOrElse("")))
                      .map(nodes =>
                            (nodes._1.arch,
                             nodes._1.clusterNamespace,
                             nodes._1.heartbeatIntervals,
                             nodes._1.id,
                             nodes._1.isNamespaceScoped,
                             nodes._1.lastHeartbeat,
                             nodes._1.lastUpdated,
                             nodes._1.msgEndPoint,
                             nodes._1.name,
                             nodes._1.nodeType,
                             (nodes._2._1 ++ "/" ++ nodes._2._3),
                             nodes._1.pattern,
                             nodes._1.publicKey,
                             nodes._1.regServices,
                             nodes._1.softwareVersions,
                             StrConstants.hiddenPw,
                             nodes._1.userInput))
                  
                  val filteredNodes =
                    for {
                      nodes <-
                        (nodes joinLeft NodeGroupAssignmentTQ on ((someNode, assignment) => someNode._4 === assignment.node))
                          .joinLeft(NodeGroupTQ).on(_._2.map(_.group) === _.group)    // ((A left Join B) Left Join C)
                          .map(record =>
                                (record._1._1._4,              // Node.id
                                 (record._1._1._1,          // Node.arch
                                  record._1._1._2,          // Node.clusterNamespace
                                  record._2.map(_.name),    // NodeGroup.group
                                  record._1._1._3,          // Node.heartbeatIntervals
                                  record._1._1._5,          // Node.isNamespaceScoped
                                  record._1._1._6,          // Node.lastHeartbeat
                                  record._1._1._7,          // Node.lastUpdated
                                  record._1._1._8,          // Node.msgEndPoint
                                  record._1._1._9,          // Node.name
                                  record._1._1._10,         // Node.nodeType
                                  record._1._1._11,         // Node.owner
                                  record._1._1._12,         // Node.pattern
                                  record._1._1._13,         // Node.publicKey
                                  record._1._1._14,         // Node.regServices
                                  record._1._1._15,         // Node.softwareVersions
                                  record._1._1._16,         // Node.token
                                  record._1._1._17)))  // Node.userInput
                    } yield (nodes)
                  
                  db.run(Compiled(filteredNodes).result.transactionally).map({
                    result =>
                      logger.debug(s"GET /orgs/$organization/nodes - result size: ${result.size}")
                      val nodes: Map[String, Node] =
                        result.map(result =>
                          result._1 -> new Node(node = result._2)).toMap
                         
                      val code: StatusCode = if (nodes.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                    (code, GetNodesResponse(nodes, 0))
                  })
                }) // end of complete
              }
          } // end of exchAuth
    }
  
  def nodes(identity: Identity2): Route =
    path("orgs" / Segment / "nodes") {
      organization =>
        exchAuth(TNode(OrgAndId(organization, "#").toString), Access.READ, validIdentity = identity) {
          _ =>
            getNodes(identity, organization)
        }
    }
}
