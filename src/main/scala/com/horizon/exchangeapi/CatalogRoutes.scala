package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._

import scala.concurrent.ExecutionContext
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import com.horizon.exchangeapi.tables._
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.read
import slick.jdbc.PostgresProfile.api._

import scala.util.{Failure, Success}

// Provides routes for browsing the services and patterns in the IBM catalog
@Path("/v1/catalog")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait CatalogRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def catalogRoutes: Route = catalogGetNodes ~ catalogGetServicesRoute ~ catalogGetPatternsRoute ~ catalogGetServicesAll ~ catalogGetPatternsAll

  // ====== GET /catalog/services ================================
  @GET
  @Path("services")
  @Operation(summary = "Returns services in the IBM catalog", description = "Returns public service definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
          examples = Array(
            new ExampleObject(
              value ="""{
  "services": {
    "orgid/servicename": {
      "owner": "string",
      "label": "string",
      "description": "blah blah",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "1.2.3",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.221Z[UTC]"
    },
    "orgid/servicename2": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "4.5.6",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [
        {
          "url": "string",
          "org": "string",
          "version": "[1.0.0,INFINITY)",
          "versionRange": "[1.0.0,INFINITY)",
          "arch": "string"
        }
      ],
      "userInput": [
        {
          "name": "foo",
          "label": "The Foo Value",
          "type": "string",
          "defaultValue": "bar"
        }
      ],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.680Z[UTC]"
    },
    "orgid/servicename3": {
      "owner": "string",
      "label": "string",
      "description": "fake",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "string",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "",
      "deploymentSignature": "",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-12-13T15:38:57.679Z[UTC]"
    },
      ...
  },
  "lastIndex": 0
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[GetServicesResponse])
        ))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetServicesRoute: Route = (path("catalog" / "services") & get & parameter(('orgtype.?))) { (orgType) =>
    exchAuth(TService(OrgAndId("*","*").toString),Access.READ_ALL_SERVICES) { _ =>
        complete({
          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType(orgType.getOrElse("IBM")) join ServicesTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          db.run(svcQuery.result).map({ list =>
            logger.debug("GET /catalog/services result size: "+list.size)
            val services = list.map(a => a.service -> a.toService).toMap
            val code = if (services.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetServicesResponse(services, 0))
          })
        }) // end of complete
    } // end of exchAuth
  }

  // ====== GET /catalog/patterns ================================
  @GET
  @Path("patterns")
  @Operation(summary = "Returns patterns in the IBM catalog", description = "Returns public pattern definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "patterns": {
    "orgid/patternname": {
      "owner": "string",
      "label": "My Pattern",
      "description": "blah blah",
      "public": true,
      "services": [
        {
          "serviceUrl": "string",
          "serviceOrgid": "string",
          "serviceArch": "string",
          "agreementLess": false,
          "serviceVersions": [
            {
              "version": "4.5.6",
              "deployment_overrides": "string",
              "deployment_overrides_signature": "a",
              "priority": {
                "priority_value": 50,
                "retries": 1,
                "retry_durations": 3600,
                "verified_durations": 52
              },
              "upgradePolicy": {
                "lifecycle": "immediate",
                "time": "01:00AM"
              }
            }
          ],
          "dataVerification": {
            "metering": {
              "tokens": 1,
              "per_time_unit": "min",
              "notification_interval": 30
            },
            "URL": "",
            "enabled": true,
            "interval": 240,
            "check_rate": 15,
            "user": "",
            "password": ""
          },
          "nodeHealth": {
            "missing_heartbeat_interval": 600,
            "check_agreement_status": 120
          }
        }
      ],
      "userInput": [],
      "agreementProtocols": [
        {
          "name": "Basic"
        }
      ],
      "lastUpdated": "2019-05-14T16:34:34.194Z[UTC]"
    },
      ...
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetPatternsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetPatternsRoute: Route = (path("catalog" / "patterns") & get & parameter(('orgtype.?))) { (orgType) =>
    exchAuth(TPattern(OrgAndId("*","*").toString),Access.READ_ALL_PATTERNS) { _ =>
      complete({
        val svcQuery = for {
          (_, svc) <- OrgsTQ.getOrgidsOfType(orgType.getOrElse("IBM")) join PatternsTQ.rows on ((o, s) => {o === s.orgid && s.public})
        } yield svc

        db.run(svcQuery.result).map({ list =>
          logger.debug("GET /catalog/patterns result size: "+list.size)
          val patterns = list.map(a => a.pattern -> a.toPattern).toMap
          val code = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetPatternsResponse(patterns, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }
  
  // ====== GET /catalog/{orgid}/nodes ===================================
  @GET
  @Path("{orgid}/nodes")
  @Operation(description = "Returns all nodes with node status and errors",
             summary = "Returns all nodes (edge devices) with node status and errors. Can be run by any user or agbot.",
             parameters =
               Array(new Parameter(description = "Filter results to only include nodes with this architecture (can include % for wildcard - the URL encoding for % is %25)",
                                   in = ParameterIn.QUERY,
                                   name = "arch",
                                   required = false),
                     new Parameter(description = "Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)",
                                   in = ParameterIn.QUERY,
                                   name = "id",
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
                                   name = "orgid",
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
          "properties": []
        },
        {
          "url": "string",
          "numAgreements": 0,
          "configState": "active",
          "policy": "",
          "properties": []
        },
        {
          "url": "string",
          "numAgreements": 0,
          "configState": "active",
          "policy": "",
          "properties": []
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
      "lastUpdated": "string"
    },
      ...
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetNodesResponse])
          )
        )),
      new responses.ApiResponse(description = "invalid credentials", responseCode = "401"),
      new responses.ApiResponse(description = "access denied", responseCode = "403"),
      new responses.ApiResponse(description = "not found", responseCode = "404")))
  def catalogGetNodes: Route =
    (path("catalog" /  Segment / "nodes") & get & parameter(('arch.?, 'id.?, 'name.?, 'type.?, 'owner.?))) {
      (orgid: String, arch: Option[String], id: Option[String], name: Option[String], nodeType: Option[String], owner: Option[String]) =>
        exchAuth(TNode(OrgAndId(orgid,"#").toString), Access.READ) {
          ident =>
            validateWithMsg(GetNodesUtils.getNodesProblem(nodeType)) {
              complete({
                implicit val jsonFormats: Formats = DefaultFormats
                val ownerFilter: Option[String] =
                  if(ident.isAdmin || ident.role.equals(AuthRoles.Agbot))
                    owner
                  else
                    Some(ident.identityString)
                
                case class Node(arch: String,
                                connectivity: Option[Map[String, Boolean]],
                                errors: Option[List[String]],
                                heartbeatIntervals: NodeHeartbeatIntervals,
                                id: String,
                                lastHeartbeat: Option[String] = None,
                                lastUpdatedNode: String,
                                lastUpdatedNodeError: Option[String],
                                lastUpdatedNodeStatus: Option[String],
                                msgEndPoint: String,
                                name: String,
                                nodeType: String,
                                owner: String,
                                pattern: String,
                                publicKey: String,
                                token: String,
                                services: Option[List[OneService]],
                                softwareVersions: Map[String, String],
                                registeredServices: Option[List[RegService]],
                                runningServices: Option[String],
                                userInput: Option[List[OneUserInputService]])
                
                logger.debug("ORGID: " + orgid)
                logger.debug("ARCH: " + arch)
                logger.debug("ID: " + id)
                logger.debug("NAME: " + name)
                logger.debug("NODETYPE: " + nodeType)
                logger.debug("OWNER: " + owner)
      
                val getNodes =
                  for {
                    nodes ← NodesTQ.rows
                                   .filterOpt(arch)((node, arch) ⇒ node.arch like arch)
                                   .filterOpt(id)((node, id) ⇒ node.id like id)
                                   .filterOpt(name)((node, name) ⇒ node.name like name)
                                   /*.filterOpt(nodeType)(
                                     (node, nodeType) ⇒
                                       node.nodeType.replace("", "device") === nodeType.toLowerCase)*/
                                   .filter(_.orgid === orgid)
                                   .filterOpt(ownerFilter)((node, ownerFilter) ⇒ node.owner like ownerFilter)
                                   .joinLeft(NodeErrorTQ.rows/*.filterOpt(id)((nodeErrors, id) ⇒ nodeErrors.nodeId like id)*/)
                                     .on(_.id === _.nodeId)
                                   .joinLeft(NodeStatusTQ.rows/*.filterOpt(id)((nodeErrors, id) ⇒ nodeErrors.nodeId like id)*/)
                                     .on(_._1.id === _.nodeId)
                                   .sortBy(_._1._1.id.asc)
                                   .map(
                                     node ⇒
                                       (node._1._1.arch,
                                        node._1._1.id,
                                        node._1._1.heartbeatIntervals,
                                        node._1._1.lastHeartbeat,
                                        node._1._1.lastUpdated,
                                        node._1._1.msgEndPoint,
                                        node._1._1.name,
                                        node._1._1.nodeType.replace("", "device"),
    //                                    node._1._1.orgid,
                                        node._1._1.owner,
                                        node._1._1.pattern,
                                        node._1._1.publicKey,
                                        node._1._1.regServices,
                                        node._1._1.softwareVersions,
                                        (if(ident.isSuperUser)
                                          node._1._1.id.substring(0,0)
                                         else
                                          node._1._1.token),
                                        node._1._1.userInput,
                                        node._1._2,
                                        node._2))
                                   .result
                                   .map(
                                     results ⇒
                                       results.map(
                                         node ⇒
                                           Node(arch = node._1,
                                                connectivity =
                                                  if(node._17.nonEmpty &&
                                                     node._17.get.connectivity != "")
                                                    Some(read[Map[String, Boolean]](node._17.get.connectivity))
                                                  else
                                                    None,//Map[String, Boolean](),
                                                errors =
                                                  if(node._16.nonEmpty &&
                                                     node._16.get.errors != "")
                                                    Some(read[List[String]](node._16.get.errors))
                                                  else
                                                    None,//List[String](),
                                                id = node._2,
                                                heartbeatIntervals =
                                                  if(node._3 != "")
                                                    read[NodeHeartbeatIntervals](node._3)
                                                  else
                                                    NodeHeartbeatIntervals(0, 0, 0),
                                                lastHeartbeat = node._4,
                                                lastUpdatedNode = node._5,
                                                lastUpdatedNodeError =
                                                  if(node._16.nonEmpty)
                                                    Some(node._16.get.lastUpdated)
                                                  else
                                                    None,
                                                lastUpdatedNodeStatus =
                                                  if(node._17.nonEmpty)
                                                    Some(node._17.get.lastUpdated)
                                                  else
                                                    None,
                                                msgEndPoint = node._6,
                                                name = node._7,
                                                nodeType = node._8,
                                                owner = node._9,
                                                pattern = node._10,
                                                publicKey = node._11,
                                                registeredServices =
                                                  if(node._12 != "")
                                                    Some(read[List[RegService]](node._12).map(rs => RegService(rs.url,rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties)))
                                                  else
                                                    None,//List[RegService]()),
                                                runningServices =
                                                  if(node._17.nonEmpty)
                                                    Some(node._17.get.runningServices)
                                                  else
                                                    None,
                                                services =
                                                  if(node._17.nonEmpty &&
                                                     node._17.get.services != "")
                                                    Some(read[List[OneService]](node._17.get.services))
                                                  else
                                                    None,//List[OneService](),
                                                softwareVersions =
                                                  if(node._13 != "")
                                                    read[Map[String, String]](node._13)
                                                  else
                                                    Map[String, String](),
                                                token =
                                                  if(node._14 == "")
                                                    StrConstants.hiddenPw
                                                  else
                                                    node._14,
                                                userInput =
                                                  if(node._15 != "")
                                                    Some(read[List[OneUserInputService]](node._15))
                                                  else
                                                    None)).toList)
                    
                  } yield(nodes)
              
                db.run(getNodes.asTry).map({
                  case Success(nodes) ⇒
                    if(nodes.nonEmpty)
                      (HttpCode.OK, case class(nodes: List[Node]))
                    else
                      (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                  case Failure(t) ⇒
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
                })
              }) // end of complete
            }
        } // end of exchAuth
  }

  // ====== GET /catalog/{orgid}/services ================================
  @GET
  @Path("{orgid}/services")
  @Operation(summary = "Returns all services", description = "Returns all service definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this public setting"),
      new Parameter(name = "url", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this url (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "version", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this version (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "arch", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this arch (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "nodetype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services that are deployable on this nodeType. Valid values: devices or clusters"),
      new Parameter(name = "requiredurl", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services that use this service with this url (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "services": {
    "orgid/servicename": {
      "owner": "string",
      "label": "string",
      "description": "blah blah",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "1.2.3",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.221Z[UTC]"
    },
    "orgid/servicename2": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "4.5.6",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [
        {
          "url": "string",
          "org": "string",
          "version": "[1.0.0,INFINITY)",
          "versionRange": "[1.0.0,INFINITY)",
          "arch": "string"
        }
      ],
      "userInput": [
        {
          "name": "foo",
          "label": "The Foo Value",
          "type": "string",
          "defaultValue": "bar"
        }
      ],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.680Z[UTC]"
    },
    "orgid/servicename3": {
      "owner": "string",
      "label": "string",
      "description": "fake",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "string",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "deployment": "",
      "deploymentSignature": "",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-12-13T15:38:57.679Z[UTC]"
    },
      ...
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetServicesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetServicesAll: Route = (path("catalog" / Segment / "services") & get & parameter(('owner.?, 'public.?, 'url.?, 'version.?, 'arch.?, 'nodetype.?, 'requiredurl.?))) { (orgid, owner, public, url, version, arch, nodetype, requiredurl) =>
    exchAuth(TService(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validateWithMsg(GetServicesUtils.getServicesProblem(public, version, nodetype)) {
        complete({
          var q = ServicesTQ.getAllServices(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          url.foreach(url => { if (url.contains("%")) q = q.filter(_.url like url) else q = q.filter(_.url === url) })
          version.foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
          arch.foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })
          nodetype.foreach(nt => { if (nt == "device") q = q.filter(_.deployment =!= "") else if (nt == "cluster") q = q.filter(_.clusterDeployment =!= "") })

          // We are cheating a little on this one because the whole requiredServices structure is serialized into a json string when put in the db, so it has a string value like
          // [{"url":"mydomain.com.rtlsdr","version":"1.0.0","arch":"amd64"}]. But we can still match on the url.
          requiredurl.foreach(requrl => {
            val requrl2 = "%\"url\":\"" + requrl + "\"%"
            q = q.filter(_.requiredServices like requrl2)
          })

          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType("IBM") join ServicesTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          var allServices : Map[String, Service] = null
          db.run(q.result.flatMap({ list =>
            logger.debug("GET /catalog/"+orgid+"/services org result size: "+list.size)
            val services = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.service -> e.toService).toMap
            allServices = services
            svcQuery.result
          })).map({ list =>
            logger.debug("GET /catalog/"+orgid+"/services IBM result size: "+list.size)
            val services = list.map(a => a.service -> a.toService).toMap
            allServices = allServices ++ services
            val code = if (allServices.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetServicesResponse(allServices, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  /* ====== GET /catalog/{orgid}/patterns ================================ */
  @GET
  @Path("{orgid}/patterns")
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this public setting"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this description (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "patterns": {
    "orgid/patternname": {
      "owner": "string",
      "label": "My Pattern",
      "description": "blah blah",
      "public": true,
      "services": [
        {
          "serviceUrl": "string",
          "serviceOrgid": "string",
          "serviceArch": "string",
          "agreementLess": false,
          "serviceVersions": [
            {
              "version": "4.5.6",
              "deployment_overrides": "string",
              "deployment_overrides_signature": "a",
              "priority": {
                "priority_value": 50,
                "retries": 1,
                "retry_durations": 3600,
                "verified_durations": 52
              },
              "upgradePolicy": {
                "lifecycle": "immediate",
                "time": "01:00AM"
              }
            }
          ],
          "dataVerification": {
            "metering": {
              "tokens": 1,
              "per_time_unit": "min",
              "notification_interval": 30
            },
            "URL": "",
            "enabled": true,
            "interval": 240,
            "check_rate": 15,
            "user": "",
            "password": ""
          },
          "nodeHealth": {
            "missing_heartbeat_interval": 600,
            "check_agreement_status": 120
          }
        }
      ],
      "userInput": [],
      "agreementProtocols": [
        {
          "name": "Basic"
        }
      ],
      "lastUpdated": "2019-05-14T16:34:34.194Z[UTC]"
    },
      ...
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetPatternsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetPatternsAll: Route = (path("catalog" / Segment / "patterns") & get & parameter(('idfilter.?, 'owner.?, 'public.?, 'label.?, 'description.?))) { (orgid, idfilter, owner, public, label, description) =>
    exchAuth(TPattern(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
        complete({
          logger.debug("ORGID: "+ orgid)
          //var q = PatternsTQ.rows.subquery
          var q = PatternsTQ.getAllPatterns(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
          description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType("IBM") join PatternsTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          var allPatterns : Map[String, Pattern] = null
          db.run(q.result.flatMap({ list =>
            logger.debug("GET /catalog/"+orgid+"/patterns org result size: "+list.size)
            val patterns = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
            allPatterns = patterns
            svcQuery.result
          })).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/patterns IBM result size: "+list.size)
            val patterns = list.map(a => a.pattern -> a.toPattern).toMap
            allPatterns = allPatterns ++ patterns
            val code = if (allPatterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetPatternsResponse(allPatterns, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

}
