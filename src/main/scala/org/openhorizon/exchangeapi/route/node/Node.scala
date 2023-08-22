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
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, PUT, Path}
import org.json4s.DefaultFormats
import org.json4s.native.Serialization.write
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.ExchangeApiApp.{exchAuth, validateWithMsg}
import org.openhorizon.exchangeapi.auth.{Access, AccessDeniedException, AuthCache, AuthRoles, AuthenticationSupport, BadInputException, DBProcessingError, IUser, Identity, OrgAndId, Password, ResourceNotFoundException, TNode}
import org.openhorizon.exchangeapi.table.deploymentpattern.{PatternRow, Patterns, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgLimits, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.service.{SearchServiceKey, SearchServiceTQ, ServicesTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth, VersionRange}
import org.openhorizon.exchangeapi.{table}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Compiled, CompiledExecutable}

import java.lang.IllegalStateException
import java.sql.Timestamp
import java.time.ZoneId
import scala.concurrent.ExecutionContext
import scala.util.control.Breaks.{break, breakable}
import scala.util.{Failure, Success}

trait Node extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in wDirectives.ith ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // =========== DELETE /orgs/{orgid}/nodes/{id} ===============================
  @DELETE
  @Path("nodes/{id}")
  @Operation(summary = "Deletes a node", description = "Deletes a node (RPi), and deletes the agreements stored for this node (but does not actually cancel the agreements between the node and agbots). Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def deleteNode(node: String,
                 organization: String,
                 resource: String): Route =
    delete {
      logger.debug(s"Doing DELETE /orgs/$organization/nodes/$node")
      val compositeId: String = OrgAndId(organization, node).toString
      exchAuth(TNode(compositeId), Access.WRITE) { _ =>
        complete({
          // remove does *not* throw an exception if the key does not exist
          db.run(NodesTQ.getNode(compositeId).delete.transactionally.asTry.flatMap({
            case Success(v) =>
              if (v > 0) { // there were no db errors, but determine if it actually found it or not
                logger.debug(s"DELETE /orgs/$organization/nodes/$node result: $v")
                AuthCache.removeNodeAndOwner(compositeId)
                ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODE, ResChangeOperation.DELETED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$organization/nodes/$node updated in changes table: $v")
              (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (t.getMessage.contains("couldn't find node")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.deleted", compositeId, t.toString))
            case Failure(t) =>
              if (t.getMessage.contains("couldn't find node")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.deleted", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of exchAuth
   }
  
  /* ====== GET /orgs/{orgid}/nodes/{id} ================================ */
  @GET
  @Path("nodes/{id}")
  @Operation(summary = "Returns a node", description = "Returns the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned")),
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
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def getNode(node: String, organization: String, resource: String): Route =
    get {
      parameter ("attribute".?) {
        attribute =>
        logger.debug(s"Doing GET /orgs/$organization/nodes/$node")
        exchAuth(TNode(resource), Access.READ) { ident =>
          val q = if (attribute.isDefined) NodesTQ.getAttribute(resource, attribute.get) else null
          validate(attribute.isEmpty || q != null, ExchMsg.translate("node.name.not.in.resource")) {
            complete({
              attribute match {
                case Some(attr) => // Only returning 1 attr of the node
                  val q = NodesTQ.getAttribute(resource, attr)
                  if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("not.a.node.attribute", attr))) else db.run(q.result).map({ list =>
                    logger.debug("GET /orgs/" + organization + "/nodes/" + node + " attribute result: " + list.size)
                    if (list.nonEmpty) (HttpCode.OK, GetNodeAttributeResponse(attr, list.head.toString)) else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found"))) // validateAccessToNode() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
                  })
                case None => // Return the whole node
                  val q = for {((node, _), group) <- (NodesTQ.getNode(resource) joinLeft NodeGroupAssignmentTQ on (_.id === _.node)).joinLeft(NodeGroupTQ).on(_._2.map(_.group) === _.group)} yield (node, group.map(_.name))
                  db.run(q.result).map({ list =>
                    logger.debug("GET /orgs/" + organization + "/nodes/" + node + " result: " + list.size)
                    if (list.nonEmpty) {
                      //val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
                      val nodes: Map[String, table.node.Node] = list.map(e => e._1.id -> e._1.toNode(ident.isSuperUser, e._2)).toMap
                      (HttpCode.OK, GetNodesResponse(nodes, 0))
                    } else {
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found"))) // validateAccessToNode() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
                    }
                  })
              }
            }) // end of complete
          } // end of validate
        } // end of exchAuth
      }
    }
  
  // =========== PATCH /orgs/{orgid}/nodes/{id} ===============================
  @PATCH
  @Path("nodes/{id}")
  @Operation(summary = "Updates 1 attribute of a node", description = "Updates some attributes of a node. This can be called by the user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    requestBody = new RequestBody(description = "Specify only **one** of the following attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "token": "abc",
  "name": "rpi3",
  "nodeType": "device",
  "pattern": "myorg/mypattern",
  "arch": "arm",
  "registeredServices": [
    {
      "url": "IBM/github.com.open-horizon.examples.cpu",
      "numAgreements": 1,
      "policy": "{}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "="
        }
      ]
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "msgEndPoint": "",
  "softwareVersions": {"horizon": "1.2.3"},
  "publicKey": "ABCDEF",
  "heartbeatIntervals": {
    "minInterval": 10,
    "maxInterval": 120,
    "intervalAdjustment": 10
  },
  "clusterNamespace": "MyNamespace",
  "isNamespaceScoped": false
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchNodesRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node")
  def patchNode(//identity: Identity,
                node: String,
                organization: String,
                resource: String): Route =
    patch {
      entity(as[PatchNodesRequest]) {
        reqBody =>
          val attributeExistence: Seq[(String, Boolean)] =
            Seq(("arch",reqBody.arch.isDefined),
                ("clusterNamespace", reqBody.clusterNamespace.isDefined),
                ("isNamespaceScoped", reqBody.isNamespaceScoped.isDefined),
                ("heartbeatIntervals", reqBody.heartbeatIntervals.isDefined),
                ("msgEndPoint", reqBody.msgEndPoint.isDefined),
                ("name", reqBody.name.isDefined),
                ("nodeType", reqBody.nodeType.isDefined),
                ("pattern", reqBody.pattern.isDefined),
                ("publicKey", reqBody.publicKey.isDefined),
                ("registeredServices", reqBody.registeredServices.isDefined),
                ("softwareVersions", reqBody.softwareVersions.isDefined),
                ("token", reqBody.token.isDefined),
                ("userInput", reqBody.userInput.isDefined))
          exchAuth(TNode(resource), Access.WRITE) {
            identity =>
          // Mutual exlusion check amongst given attributes that could be apart of the request body.
          validateWithMsg(if (attributeExistence.filter(_._2).sizeIs == 1)
                            None
                          else
                            Option(ExchMsg.translate("bad.input"))) {
            validateWithMsg(if (reqBody.token.isDefined &&
                                reqBody.token.get.isEmpty)
                              Option(ExchMsg.translate("token.cannot.be.empty.string"))
                            else
                              None) {
              complete({
                logger.debug(s"Doing PATCH /orgs/$organization/nodes/$node")
                // Synchronize the timestamps of the records we are changing. This helps debugging/troubleshooting from the records and logs.
                val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
                implicit val formats: DefaultFormats.type = DefaultFormats
                // Multi-threaded rest requests. Need to break records into blocks based on request.
                val session: String = Password.fastHash(password = s"patch$organization$node${identity.identityString}${changeTimestamp.getTime.toString}")
                
                // Have a single attribute to update, retrieve its name.
                val validAttribute: String =
                  attributeExistence.filter(attribute => attribute._2).head._1
                
                val getPatternBase: Query[Patterns, PatternRow, Seq] =
                  PatternsTQ.filter(_.pattern === reqBody.pattern)
                
                val matchingPatterns: CompiledExecutable[Rep[Int], Int] =
                  if (identity.isSuperUser)
                    Compiled(getPatternBase.map(_.pattern).length)
                  else
                    Compiled(getPatternBase.filter(pattern => pattern.public || pattern.orgid === organization)
                                           .map(pattern => (pattern.orgid, pattern.public))
                                           .join(NodesTQ.filter(_.id === resource)
                                                        .filter(node => (node.pattern =!= "" || (node.pattern === "" && node.publicKey === "")))
                                                        .map(_.orgid))
                                           .on((pattern, node) => (pattern._1 === node || pattern._2))
                                           .map(_._1)
                                           .length)
                
                val servicesToSearch: Seq[SearchServiceKey] = {
                  if (validAttribute == "userInput") {
                    reqBody.userInput.get.map(
                      service =>
                        SearchServiceKey(architecture = (if (service.serviceArch.isEmpty || service.serviceArch.get == "") "%" else service.serviceArch.get),
                                         domain = service.serviceUrl,
                                         organization = service.serviceOrgid,
                                         session = session,
                                         version = VersionRange(service.serviceVersionRange.getOrElse("[0,1]")).singleVersion.getOrElse("%").toString))
                           .distinct
                  }
                  else
                    Seq()
                }
                
                val authorizedServices: Query[(Rep[String], Rep[String], Rep[String], Rep[String]), (String, String, String, String), Seq] =
                    ServicesTQ.filterIf(!identity.isSuperUser) (
                                service =>
                                  (service.orgid === organization ||
                                   service.public))
                              .map(
                                service =>
                                  (service.arch,
                                   service.orgid,
                                   service.url,
                                   service.version))
                
                val patchNode =
                  for {
                    // ---------- pattern --------------
                    matchedPatterns <-
                      if (validAttribute == "pattern" && reqBody.pattern.get.nonEmpty)
                        matchingPatterns.result
                      else
                        DBIO.successful(1)
                    
                    // Check if a vaild pattern exist for this User Archetype, and make sure this Node is not
                    // already using a Deployment Policy.
                    _ <-
                      if (matchedPatterns == 0)
                        DBIO.failed(new BadInputException(msg = ExchMsg.translate("pattern.not.in.exchange")))
                      else
                        DBIO.successful(())
                    
                    // ---------- publicKey ------------
                    validPublicKey <-
                      if ((identity.role.equals(AuthRoles.Node) ||
                           identity.isSuperUser) &&
                          validAttribute == "publicKey") {
                          if (reqBody.publicKey.get.nonEmpty)
                            Compiled(NodesTQ.getNode(resource)
                                            .filter(_.publicKey === "")
                                            .map(_.id)
                                            .length).result
                          else
                            DBIO.successful(1)
                      } else if (validAttribute == "publicKey")
                        DBIO.failed(new AccessDeniedException(msg = ExchMsg.translate("access.denied")))
                      else
                        DBIO.successful(1)
                    
                    _ <-
                      if (validPublicKey == 0)
                        DBIO.failed(new BadInputException(msg = ExchMsg.translate("public.key.no.replace")))
                      else
                        DBIO.successful(())
                    
                    // ---------- token ----------------
                    unsetPublicKeys <-
                      if (validAttribute == "token")
                        Compiled(NodesTQ.getNode(resource).filter(_.publicKey === "").map(_.id).length).result
                      else
                        DBIO.successful(1)
                    
                    // Do not allow setting the Node's Token if its Public Key is already set.
                    _ <-
                      if (unsetPublicKeys == 0)
                        DBIO.failed(new IllegalStateException(ExchMsg.translate("node.public.key.not.token", resource)))
                      else
                        DBIO.successful(())
                    
                    hashedPW =
                      if (validAttribute == "token")
                        Password.fastHash(reqBody.token.get)
                      else
                        ""
                    
                    // ---------- userInput ------------
                    // Has to have valid matches with defined services. Must be authorized to use service.
                    _ <-
                      if (validAttribute == "userInput") {
                        // Load services to search on
                        SearchServiceTQ ++= servicesToSearch
                      } else
                        DBIO.successful(())
                    
                    unmatchedServices <-
                      if (validAttribute == "userInput") {
                        // Compare our request input with what we have, factoring in authorization.
                        Compiled(authorizedServices.joinRight(
                                                      SearchServiceTQ.filter(_.session === session)
                                                                     .map(
                                                                       key =>
                                                                         (key.architecture,
                                                                          key.domain,
                                                                          key.organization,
                                                                          key.version)))
                                                   .on(
                                                     (service, search) =>
                                                     {service._2 === search._3 &&
                                                      service._3 === search._2 &&
                                                      (service._1 like search._1) &&
                                                      (service._4 like search._4)})
                                                   .filter(_._1.isEmpty)
                                                   .map(
                                                     service =>
                                                       (service._2._1,
                                                        service._2._2,
                                                        service._2._3,
                                                        service._2._4)))
                          .result
                      } else
                        DBIO.successful(Seq(("", "", "", ""))) // Needs to be of the same type.
                    
                    _ <-
                      if (validAttribute == "userInput") {
                        // Either all of our requested services are valid or none of them are.
                        if (0 < unmatchedServices.length) {
                          // Database will auto-remove our inputs when the transaction rolls-back.
                          // Vector(organization/domain_version_architecture, ...)
                          DBIO.failed(new BadInputException(msg = ExchMsg.translate("services.not.found", unmatchedServices.map(service => service._3 + "/" + service._2 + "_" + service._4 + "_" + service._1).toString)))
                        } else {
                          // Remove our request inputs if everything is valid.
                          Compiled(SearchServiceTQ.filter(_.session === session)).delete
                        }
                      } else
                        DBIO.successful(())
                    
                    // ---------- Node Record Change ---
                    nodesUpdated <-
                      if (validAttribute == "clusterNamespace")
                        Compiled(NodesTQ.getNode(resource)
                                        .map(node => (node.clusterNamespace, node.lastUpdated)))
                                        .update((reqBody.clusterNamespace,
                                                 fixFormatting(changeTimestamp.toInstant
                                                                              .atZone(ZoneId.of("UTC"))
                                                                              .withZoneSameInstant(ZoneId.of("UTC"))
                                                                              .toString)))
                      else if (validAttribute == "isNamespaceScoped")
                        Compiled(NodesTQ.getNode(resource)
                                        .map(node => (node.isNamespaceScoped, node.lastUpdated)))
                                        .update((reqBody.isNamespaceScoped.get,
                                                 fixFormatting(changeTimestamp.toInstant
                                                                              .atZone(ZoneId.of("UTC"))
                                                                              .withZoneSameInstant(ZoneId.of("UTC"))
                                                                              .toString)))
                      else
                        Compiled(NodesTQ.getNode(resource)
                                        .map(node =>
                                              (validAttribute match {
                                                case "arch" => node.arch
                                                case "heartbeatIntervals" => node.heartbeatIntervals
                                                case "msgEndPoint" => node.msgEndPoint
                                                case "name" => node.name
                                                case "nodeType" => node.nodeType
                                                case "pattern" => node.pattern
                                                case "publicKey" => node.publicKey
                                                case "registeredServices" => node.regServices
                                                case "softwareVersions" => node.softwareVersions
                                                case "token" => node.token
                                                case "userInput" => node.userInput},
                                                node.lastUpdated)))
                                        .update((validAttribute match {
                                                  case "arch" => reqBody.arch.get
                                                  case "heartbeatIntervals" => write(reqBody.heartbeatIntervals.get)
                                                  case "msgEndPoint" => reqBody.msgEndPoint.get
                                                  case "name" => reqBody.name.get
                                                  case "nodeType" => reqBody.nodeType.get
                                                  case "pattern" => reqBody.pattern.get
                                                  case "publicKey" => reqBody.publicKey.get
                                                  case "registeredServices" => write(reqBody.registeredServices.get)
                                                  case "softwareVersions" => write(reqBody.softwareVersions.get)
                                                  case "token" => hashedPW
                                                  case "userInput" => write(reqBody.userInput.get)},
                                                  fixFormatting(changeTimestamp.toInstant
                                                                               .atZone(ZoneId.of("UTC"))
                                                                               .withZoneSameInstant(ZoneId.of("UTC"))
                                                                               .toString)))
                    
                    _ <-
                      if (nodesUpdated == 0||
                          1 < nodesUpdated)
                        throw(new ResourceNotFoundException())
                      else
                        DBIO.successful(())
                    
                    // ---------- Resource Change Log --
                    _ <-
                      ResourceChangesTQ +=
                      ResourceChangeRow(category = ResChangeCategory.NODE.toString,
                                        changeId = 0L,
                                        id = node,
                                        lastUpdated = changeTimestamp,
                                        operation = ResChangeOperation.MODIFIED.toString,
                                        orgId = organization,
                                        public = "false",
                                        resource = ResChangeResource.NODE.toString)
                    
                    // ---------- Update Auth Cache ----
                    _ <-
                      if (validAttribute == "token") {
                        AuthCache.putNode(resource, hashedPW, reqBody.token.get)
                        DBIO.successful(())
                      }
                      else
                        DBIO.successful(())
                  } yield ()
                
                db.run(patchNode.transactionally.asTry)
                  .map({
                    case Success(_) =>
                      (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.attribute.updated", validAttribute, resource)))
                    case Failure(t: org.postgresql.util.PSQLException) =>
                      ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.inserted.or.updated", resource, t.getMessage))
                    case Failure(t: AccessDeniedException) =>
                      (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, t.getMessage))
                    case Failure(t: ResourceNotFoundException) =>
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, t.getMessage))
                    case Failure(t) =>
                      (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", resource, t.getMessage)))
                  })
              })
            }
          }
      }
      }
    }
  
  // =========== PUT /orgs/{orgid}/nodes/{id} ===============================
  @PUT
  @Path("nodes/{id}")
  @Operation(
    summary = "Add/updates a node",
    description = "Adds a new edge node, or updates an existing node. This must be called by the user to add a node, and then can be called by that user or node to update itself.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
        in = ParameterIn.PATH,
        description = "ID of the node."
      ),
      new Parameter(name = "noheartbeat", in = ParameterIn.QUERY, required = false, description = "If set to 'true', skip the step to update the lastHeartbeat field.")
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "token": "abc",
  "name": "rpi3",
  "nodeType": "device",
  "pattern": "myorg/mypattern",
  "arch": "arm",
  "registeredServices": [
    {
      "url": "IBM/github.com.open-horizon.examples.cpu",
      "numAgreements": 1,
      "policy": "{}",
      "properties": [
        {
          "name": "arch",
          "value": "arm",
          "propType": "string",
          "op": "="
        }
      ]
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "msgEndPoint": "",
  "softwareVersions": {"horizon": "1.2.3"},
  "publicKey": "ABCDEF",
  "heartbeatIntervals": {
    "minInterval": 10,
    "maxInterval": 120,
    "intervalAdjustment": 10
  },
  "clusterNamespace": "MyNamespace",
  "isNamespaceScoped": false
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PutNodesRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource add/updated - response body:",
        content = Array(
          new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse]))
        )
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
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
  def putNode(node: String,
              organization: String,
              resource: String): Route =
    put {
      parameter ("noheartbeat".?) {
        noheartbeat =>
        entity (as[PutNodesRequest]) {
          reqBody =>
            logger.debug(s"Doing PUT /orgs/$organization/nodes/$node")
            var orgMaxNodes = 0
            exchAuth(TNode(resource), Access.WRITE) { ident =>
              validateWithMsg(reqBody.getAnyProblem(node, noheartbeat)) {
                complete({
                  val noHB: Boolean = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
                  var orgLimitMaxNodes = 0
                  var fivePercentWarning = false
                  var hashedPw = ""
                  val owner: String = ident match {
                    case IUser(creds) => creds.id;
                    case _ => ""
                  }
                  val patValidateAction = if (reqBody.pattern != "") PatternsTQ.getPattern(reqBody.pattern).length.result else DBIO.successful(1)
                  val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds // to check that the services referenced in userInput exist
                  db.run(patValidateAction.asTry.flatMap({ case Success(num) => // Check if pattern exists, then get services referenced
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " pattern validation: " + num)
                    if (num > 0) valServiceIdActions.asTry else DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(v) => // Check if referenced services exist, then get whether node is using policy
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " service validation: " + v)
                    var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                    breakable {
                      for ((len, index) <- v.zipWithIndex) {
                        if (len <= 0) {
                          invalidIndex = index
                          break()
                        }
                      }
                    }
                    if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(resource).result.asTry else {
                      val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch) else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                      DBIO.failed(new Throwable(errStr)).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(v) => // Check if node is using policy, then get org limits
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " policy related attrs: " + v)
                    if (v.nonEmpty) {
                      val (existingPattern, existingPublicKey) = v.head
                      if (reqBody.pattern != "" && existingPattern == "" && existingPublicKey != "") DBIO.failed(new Throwable(ExchMsg.translate("not.pattern.when.policy"))).asTry else OrgsTQ.getLimits(organization).result.asTry // they are not trying to switch from policy to pattern, so we can continue
                    } else OrgsTQ.getLimits(organization).result.asTry // node doesn't exit yet, we can continue
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(orgLimits) => // check total number of nodes
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " orgLimits: " + orgLimits)
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " orgLimits.head: " + orgLimits.head)
                    val limits: OrgLimits = OrgLimits.toOrgLimit(orgLimits.head)
                    orgLimitMaxNodes = limits.maxNodes
                    orgMaxNodes = orgLimitMaxNodes
                    NodesTQ.getAllNodes(organization).length.result.asTry
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(totalNodes) => // verify total nodes within org limit, then get numOwned
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " total number of nodes in org: " + totalNodes)
                    if (orgLimitMaxNodes == 0) NodesTQ.getNumOwned(owner).result.asTry // no limit set
                    else if (totalNodes >= orgLimitMaxNodes) DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.org.max.limit.of.nodes", totalNodes, orgLimitMaxNodes))).asTry else if ((orgLimitMaxNodes - totalNodes) <= orgLimitMaxNodes * .05) { // if we are within 5% of the limit
                      fivePercentWarning = true // used for warning later
                      NodesTQ.getNumOwned(owner).result.asTry
                    } else NodesTQ.getNumOwned(owner).result.asTry
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(numOwned) => // Check if num nodes owned is below limit, then create/update node
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " num owned: " + numOwned)
                    val maxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
                    if (maxNodes == 0 || numOwned <= maxNodes || owner == "") // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
                      NodesTQ.getLastHeartbeat(resource).result.asTry else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.nodes", maxNodes))).asTry
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(lastHeartbeat) => lastHeartbeat.size match {
                    case 0 => val lastHB = if (noHB) None else Option(ApiTime.nowUTC)
                      hashedPw = Password.fastHash(reqBody.token)
                      (if (owner == "") // It seems like this case is an error (node doesn't exist yet, and client is not a user). The update will fail, but probably not with an error that will really explain what they did wrong.
                        reqBody.getDbUpdate(resource, organization, owner, hashedPw, lastHB) else reqBody.getDbUpsert(resource, organization, owner, hashedPw, lastHB)).transactionally.asTry
                    case 1 => val lastHB = if (noHB) lastHeartbeat.head else Option(ApiTime.nowUTC)
                      hashedPw = Password.fastHash(reqBody.token)
                      (if (owner == "") reqBody.getDbUpdate(resource, organization, owner, hashedPw, lastHB) else reqBody.getDbUpsert(resource, organization, owner, hashedPw, lastHB)).transactionally.asTry
                    case _ => DBIO.failed(new DBProcessingError(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.inserted.or.updated", resource, "Unexpected result"))).asTry
                  }
                  case Failure(t) => DBIO.failed(t).asTry
                  }).flatMap({ case Success(v) => // Add the resource to the resourcechanges table
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " result: " + v)
                    resourcechange.ResourceChange(0L, organization, node, ResChangeCategory.NODE, public = false, ResChangeResource.NODE, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                  case Failure(t) => DBIO.failed(t).asTry
                  })).map({ case Success(v) => // Check creation/update of node, and other errors
                    logger.debug("PUT /orgs/" + organization + "/nodes/" + node + " updating resource status table: " + v)
                    AuthCache.putNodeAndOwner(resource, hashedPw, reqBody.token, owner)
                    if (fivePercentWarning) (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("num.nodes.near.org.limit", organization, orgMaxNodes))) else (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.added.or.updated")))
                  case Failure(t: DBProcessingError) => t.toComplete
                  case Failure(t: org.postgresql.util.PSQLException) => ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.inserted.or.updated", resource, t.getServerErrorMessage))
                  case Failure(t) => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", resource, t.getMessage)))
                  })
                }) // end of complete
              } // end of validateWithMsg
            } // end of exchAuth
        }
      }
    }
  
  def node: Route =
    path("orgs" / Segment / "nodes" / Segment) {
      (organization,
       node) =>
            val resource: String = OrgAndId(organization, node).toString
            //exchAuth(TNode(resource), Access.WRITE) {
            //identity =>
              deleteNode(node, organization, resource) ~
              getNode(node, organization, resource) ~
              patchNode(node, organization, resource) ~
              putNode(node, organization, resource)
          //}
    }
  
  /*
     * Multi-demensional inSet with regex.
     *
     * All records are unmatched by default, unless evaluated true by the expression. Works
     * across the servicesToSearch data structure.
     *
     * Normally we would create an "input table" to dump the data we want to compare against
     * into. We would then do an inner join between the two to find all possible matched
     * combinations. This is difficult given the multi-threaded nature of the rest requests.
     * You would have to have blocks of records exclusive to each request, plus the
     * additional overhead of adding the records in at the beginning and then removing the
     * records at the end.
     */
  /*(ServicesTQ.filterIf(!ident.isSuperUser &&
                       ident.isAdmin)
                          (service =>
                            (service.orgid === orgid ||
                             service.public === true))
             .filterIf(!(ident.isAdmin ||
                         ident.isSuperUser ||
                         ident.isHubAdmin))
                            (service =>
                              ((service.orgid === orgid ||
                                service.public === true) &&
                               service.owner === ident.identityString))
             .map(service =>
                    (service.arch,
                      service.orgid,
                      service.service,
                      service.url,
                      service.version)))
    .filter({
      service =>
                                    servicesToSearch.foldLeft[Rep[Boolean]](false) {
                                      case (matched, searchKey) =>
                                        matched || ((service._1 like searchKey.architecture) &&
                                              service._2 === searchKey.organization &&
                                              service._4 === searchKey.domain &&
                                                    (service._5 like searchKey.version))
                                    }
                                })
                                .result*/
}
