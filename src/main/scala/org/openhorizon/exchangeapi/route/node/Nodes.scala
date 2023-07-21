package org.openhorizon.exchangeapi.route.node

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import jakarta.ws.rs.{GET, Path}
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.{Node, NodeType, NodesTQ}
import org.openhorizon.exchangeapi.{Access, AuthRoles, AuthenticationSupport, OrgAndId, TNode}
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

trait Nodes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /orgs/{orgid}/nodes ================================
  @GET
  @Path("nodes")
  @Operation(summary = "Returns all nodes", description = "Returns all nodes (edge devices). Can be run by any user or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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
  def getNodes(organization: String): Route =
    get {
      parameter("idfilter".?, "name".?, "owner".?, "arch".?, "nodetype".?, "clusternamespace".?, "isNamespaceScoped".as[Boolean].?) {
        (idfilter, name, owner, arch, nodetype, clusterNamespace, isNamespaceScoped) =>
          logger.debug(s"Doing GET /orgs/$organization/nodes")
          exchAuth(TNode(OrgAndId(organization, "#").toString), Access.READ) { ident =>
            validateWithMsg(GetNodesUtils.getNodesProblem(nodetype)) {
              complete({
                logger.debug(s"GET /orgs/$organization/nodes identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
                
                var q = NodesTQ.getAllNodes(organization)
                
                arch.foreach(arch => {
                  if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch)
                })
                
                clusterNamespace.foreach(namespace => {
                  if (namespace.contains("%")) q = q.filter(_.clusterNamespace like namespace) else q = q.filter(_.clusterNamespace === namespace)
                })
                
                idfilter.foreach(id => {
                  if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id)
                })
                
                if (isNamespaceScoped.isDefined)
                  q = q.filter(_.isNamespaceScoped === isNamespaceScoped.get)
                
                name.foreach(name => {
                  if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name)
                })
                
                if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) {
                  owner.foreach(owner => {
                    if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner)
                  })
                } else q = q.filter(_.owner === ident.identityString)
                
                owner.foreach(owner => {
                  if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner)
                })
                
                
                if (nodetype.isDefined) {
                  val nt: String = nodetype.get.toLowerCase
                  if (NodeType.isDevice(nt)) q = q.filter(r => {
                    r.nodeType === nt || r.nodeType === ""
                  }) else if (NodeType.isCluster(nt)) q = q.filter(_.nodeType === nt)
                }
                
                val combinedQuery = for {((nodes, _), groups) <- (q joinLeft NodeGroupAssignmentTQ on (_.id === _.node)).joinLeft(NodeGroupTQ).on(_._2.map(_.group) === _.group)} yield (nodes, groups.map(_.name))
                
                db.run(combinedQuery.result).map({ result =>
                  logger.debug(s"GET /orgs/$organization/nodes result size: ${result.size}")
                  //val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
                  val nodes: Map[String, Node] = result.map(e => e._1.id -> e._1.toNode(ident.isSuperUser, e._2)).toMap
                  val code: StatusCode = if (nodes.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                  (code, GetNodesResponse(nodes, 0))
                })
              }) // end of complete
            }
          } // end of exchAuth
      }
    }
  
  def nodes: Route =
    path("orgs" / Segment / "nodes") {
      organization =>
        getNodes(organization)
    }
}
