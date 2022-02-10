/** Services routes for all of the /orgs/{orgid}/nodes api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ArraySchema, Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext
import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.util._
import scala.util.control.Breaks._
import scala.util.matching.Regex

import org.json4s.{DefaultFormats, Formats}
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}


//====== These are the input and output structures for /orgs/{orgid}/nodes routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/nodes */
final case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int)
final case class GetNodeAttributeResponse(attribute: String, value: String)

final case class GetNMPStatusResponse(policy: Map[String,NMPStatus], lastIndex: Int)

object GetNodesUtils {
  def getNodesProblem(nodetype: Option[String]): Option[String] = {
    if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase)) return Some(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
    None
  }
}

case class NodeDetails(arch: Option[String] = None,
                       connectivity: Option[Map[String, Boolean]] = None,
                       constraints: Option[List[String]] = None,
                       errors: Option[List[Any]] = None,
                       heartbeatIntervals: Option[NodeHeartbeatIntervals] = None,
                       id: String = "",
                       lastHeartbeat: Option[String] = None,
                       lastUpdatedNode: String = "",
                       lastUpdatedNodeError: Option[String] = None,
                       lastUpdatedNodePolicy: Option[String] = None,
                       lastUpdatedNodeStatus: Option[String] = None,
                       msgEndPoint: Option[String] = None,
                       name: Option[String] = None,
                       nodeType: String = "",
                       owner: String = "",
                       orgid: String = "",
                       pattern: Option[String] = None,
                       properties: Option[List[OneProperty]] = None,
                       publicKey: Option[String] = None,
                       registeredServices: Option[List[RegService]] = None,
                       runningServices: Option[String] = None,
                       services: Option[List[OneService]] = None,
                       softwareVersions: Option[Map[String, String]] = None,
                       token: String = StrConstants.hiddenPw,
                       userInput: Option[List[OneUserInputService]] = None)

// Tried this to have names on the tuple returned from the db, but didn't work...
final case class PatternSearchHashElement(nodeType: String, publicKey: String, noAgreementYet: Boolean)

final case class PatternNodeResponse(id: String, nodeType: String, publicKey: String)
final case class PostPatternSearchResponse(nodes: List[PatternNodeResponse], lastIndex: Int)

// Leaving this here for the UI wanting to implement filtering later
final case class PostNodeErrorRequest() {
  def getAnyProblem: Option[String] = None
}
final case class PostNodeErrorResponse(nodes: scala.Seq[String])

/** Input body for POST /orgs/{orgid}/search/nodes/service -- now in OrgsRoutes */
final case class PostServiceSearchRequest(orgid: String, serviceURL: String, serviceVersion: String, serviceArch: String) {
  require(orgid!=null && serviceURL!=null && serviceVersion!=null && serviceArch!=null)
  def getAnyProblem: Option[String] = None
}
final case class PostServiceSearchResponse(nodes: scala.collection.Seq[(String, Option[String])])

final case class NodeResponse(id: String, name: String, services: List[RegService], userInput: List[OneUserInputService], msgEndPoint: String, publicKey: String, arch: String)
final case class PostSearchNodesResponse(nodes: List[NodeResponse], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/<node-id> */
final case class PutNodesRequest(token: String,
                                 name: String,
                                 nodeType: Option[String],
                                 pattern: String,
                                 registeredServices: Option[List[RegService]],
                                 userInput: Option[List[OneUserInputService]],
                                 msgEndPoint: Option[String],
                                 softwareVersions: Option[Map[String,String]],
                                 publicKey: String,
                                 arch: Option[String],
                                 heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  require(token!=null && name!=null && pattern!=null && publicKey!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  /** Halts the request with an error msg if the user input is invalid. */
  def getAnyProblem(id: String, noheartbeat: Option[String]): Option[String] = {
    if (id == "iamapikey" || id == "iamtoken") return Some(ExchMsg.translate("node.id.not.iamapikey.or.iamtoken"))
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Some(ExchMsg.translate("bad.noheartbeat.param"))
    if (token == "") return Some(ExchMsg.translate("token.must.not.be.blank"))
    // if (!NodeAgbotTokenValidation.isValid(token)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    // if (publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    if (nodeType.isDefined && !NodeType.containsString(nodeType.get)) return Some(ExchMsg.translate("invalid.node.type", NodeType.valuesAsString))
    if (pattern != "" && """.*/.*""".r.findFirstIn(pattern).isEmpty) return Some(ExchMsg.translate("pattern.must.have.orgid.prepended"))
    for (m <- registeredServices.getOrElse(List())) {
      // now we support more than 1 agreement for a MS
      // if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => return Some(s)
        case None => ;
      }
    }
    None
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { NodesTQ.validateServiceIds(userInput.getOrElse(List())) }

  /** Get the db actions to insert or update all parts of the node */
  def getDbUpsert(id: String, orgid: String, owner: String, hashedTok: String, lastHeartbeat: Option[String] /*= Some(ApiTime.nowUTC)*/): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2: Seq[RegService] = registeredServices.getOrElse(List()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties, rs.version))
    NodeRow(id,
            orgid,
            hashedTok,
            name,
            owner,
            nodeType.getOrElse(NodeType.DEVICE.toString),
            pattern,
            write(rsvc2),
            write(userInput),
            msgEndPoint.getOrElse(""),
            write(softwareVersions),
            lastHeartbeat,
            publicKey,
            arch.getOrElse(""),
            write(heartbeatIntervals),
            ApiTime.nowUTC).upsert
  }

  /** Get the db actions to update all parts of the node. This is run, instead of getDbUpsert(), when it is a node doing it,
   * because we can't let a node create new nodes. */
  def getDbUpdate(id: String, orgid: String, owner: String, hashedTok: String, lastHeartbeat: Option[String] /*= Some(ApiTime.nowUTC)*/): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2: Seq[RegService] = registeredServices.getOrElse(List()).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties, rs.version))
    NodeRow(id,
            orgid,
            hashedTok,
            name,
            owner,
            nodeType.getOrElse(NodeType.DEVICE.toString),
            pattern,
            write(rsvc2),
            write(userInput),
            msgEndPoint.getOrElse(""),
            write(softwareVersions),
            lastHeartbeat,
            publicKey,
            arch.getOrElse(""),
            write(heartbeatIntervals),
            ApiTime.nowUTC).update
  }
}

final case class PatchNodesRequest(token: Option[String], name: Option[String], nodeType: Option[String], pattern: Option[String], registeredServices: Option[List[RegService]], userInput: Option[List[OneUserInputService]], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: Option[String], arch: Option[String], heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if (token.isDefined && token.get == "") Some(ExchMsg.translate("token.cannot.be.empty.string"))
    // if (token.isDefined && !NodeAgbotTokenValidation.isValid(token.get)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    //else if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else None
  }
    /** Returns a tuple of the db action to update parts of the node, and the attribute name being updated. */
  def getDbUpdate(id: String, hashedPw: String): (DBIO[_],String) = {
    val currentTime: String = ApiTime.nowUTC
    //someday: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this node
    var dbAction: (DBIO[_], String) = (null, null)
    // nodeType intentionally missing from this 1st list of attributes, because we will default it if it is the only 1 not specified
    if(token.isEmpty && softwareVersions.isDefined && registeredServices.isDefined && name.isDefined && pattern.isDefined && userInput.isDefined && msgEndPoint.isDefined && publicKey.isDefined && arch.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions, d.regServices, d.name, d.nodeType, d.pattern, d.userInput, d.msgEndPoint, d.publicKey, d.arch, d.lastHeartbeat, d.lastUpdated)).update((id, write(softwareVersions), write(registeredServices), name.get, nodeType.getOrElse(NodeType.DEVICE.toString), pattern.get, write(userInput), msgEndPoint.get, publicKey.get, arch.get, Some(currentTime), currentTime)), "update all but token")
    } else if (token.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat, d.lastUpdated)).update((id, hashedPw, Some(currentTime), currentTime)), "token")
    } else if (softwareVersions.isDefined){
      val swVersions: String = if (softwareVersions.nonEmpty) write(softwareVersions) else ""
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat, d.lastUpdated)).update((id, swVersions, Some(currentTime), currentTime)), "softwareVersions")
    } else if (registeredServices.isDefined){
      val regSvc: String = if (registeredServices.nonEmpty) write(registeredServices) else ""
      dbAction =  ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat, d.lastUpdated)).update((id, regSvc, Some(currentTime), currentTime)), "registeredServices")
    } else if (name.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat, d.lastUpdated)).update((id, name.get, Some(currentTime), currentTime)), "name")
    } else if (nodeType.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.nodeType,d.lastHeartbeat, d.lastUpdated)).update((id, nodeType.get, Some(currentTime), currentTime)), "nodeType")
    } else if (pattern.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.pattern,d.lastHeartbeat, d.lastUpdated)).update((id, pattern.get, Some(currentTime), currentTime)), "pattern")
    } else if (userInput.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.userInput,d.lastHeartbeat, d.lastUpdated)).update((id, write(userInput), Some(currentTime), currentTime)), "userInput")
    } else if (msgEndPoint.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat, d.lastUpdated)).update((id, msgEndPoint.get, Some(currentTime), currentTime)), "msgEndPoint")
    } else if (publicKey.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat, d.lastUpdated)).update((id, publicKey.get, Some(currentTime), currentTime)), "publicKey")
    } else if (arch.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.arch,d.lastHeartbeat, d.lastUpdated)).update((id, arch.get, Some(currentTime), currentTime)), "arch")
    } else if (heartbeatIntervals.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.heartbeatIntervals,d.lastHeartbeat, d.lastUpdated)).update((id, write(heartbeatIntervals), Some(currentTime), currentTime)), "heartbeatIntervals")
    }
    dbAction
  }
}

/** Input body for POST /orgs/{orgid}/nodes/{id}/services_configstate */
final case class PostNodeConfigStateRequest(org: String, url: String, configState: String, version: Option[String]) {
  require(org!=null && url!=null && configState!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  //def logger: Logger    // get access to the logger object in ExchangeApiApp 

  def getAnyProblem: Option[String] = {
    if (configState != "suspended" && configState != "active") Some(ExchMsg.translate("configstate.must.be.suspended.or.active"))
    else if (org == "" && (url != "" || version.getOrElse("") != "")) Some(ExchMsg.translate("services.configstate.org.not.specified"))
    else if (url == "" && version.getOrElse("") != "") Some(ExchMsg.translate("services.configstate.url.not.specified"))
    else None
  }

  // Match registered service urls (which are org/url) to the input org and url
  def isMatch(compositeUrl: String): Boolean = {
    val reg: Regex = """^(\S+?)/(\S+)$""".r
    val (comporg, compurl) = compositeUrl match {
      case reg(o,u) => (o, u)
      case _ => return false   //todo: halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("configstate.must.be.suspended.or.active", compositeUrl)))
    }
    (org, url) match {
      case ("","") => true
      case ("",u) => compurl == u
      case (o,"") => comporg == o
      case (o,u) => comporg == o && compurl == u
    }
  }

  // Given the existing list of registered svcs in the db for this node, determine the db update necessary to apply the new configState
  def getDbUpdate(regServices: String, id: String): DBIO[_] = {
    if (regServices == "") return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))
    val regSvcs: Seq[RegService] = read[List[RegService]](regServices)
    if (regSvcs.isEmpty) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))

    // Copy the list of required svcs, changing configState wherever it applies
    var matchingSvcFound = false
    val newRegSvcs: Seq[RegService] = regSvcs.map({ rs =>
      if (isMatch(rs.url)) {
        matchingSvcFound = true   // warning: intentional side effect (didnt know how else to do it)
        // Match the version, either the version sent in the request body matches exactly or the wildcard version ("") was sent in
        val versionCheck = (version.getOrElse("") == rs.version.getOrElse("")) || (version.getOrElse("") == "")
        val newConfigState = if (configState != rs.configState.getOrElse("") && versionCheck) Some(configState) else rs.configState
        RegService(rs.url,rs.numAgreements, newConfigState, rs.policy, rs.properties, rs.version)
      }
      else rs
    })
    // this check is not ok, because we should not return NOT_FOUND if we find matching svc but their configState is already set the requested value
    //if (newRegSvcs.sameElements(regSvcs)) halt(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, "did not find any registeredServices that matched the given org and url criteria."))
    if (!matchingSvcFound) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("did.not.find.registered.services")))
    if (newRegSvcs == regSvcs) {
      return DBIO.successful(1)    // all the configStates were already set correctly, so nothing to do
    }

    // Convert from struct back to string and return db action to update that
    val newRegSvcsString: String = write(newRegSvcs)
    val nowTime = ApiTime.nowUTC
    (for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat,d.lastUpdated)).update((id, newRegSvcsString, Some(nowTime), nowTime))
  }
}

/** Input body for PUT /orgs/{orgid}/nodes/{id}/status */
final case class PutNodeStatusRequest(connectivity: Option[Map[String,Boolean]], services: List[OneService]) {
  require(connectivity!=null && services!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None
  var runningServices = "|"
  for(s <- services){
    runningServices = runningServices + s.orgid + "/" + s.serviceUrl + "_" + s.version + "_" + s.arch + "|"
  }
  def toNodeStatusRow(nodeId: String): NodeStatusRow = NodeStatusRow(nodeId, write(connectivity), write(services), runningServices, ApiTime.nowUTC)
}

/** Input body for PUT /orgs/{orgid}/nodes/{id}/errors */
final case class PutNodeErrorRequest(errors: List[Any]) {
  require(errors!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None

  def toNodeErrorRow(nodeId: String): NodeErrorRow = NodeErrorRow(nodeId, write(errors), ApiTime.nowUTC)
}

final case class PutNodePolicyRequest(label: Option[String], description: Option[String], properties: Option[List[OneProperty]], constraints: Option[List[String]], deployment: Option[PropertiesAndConstraints], management: Option[PropertiesAndConstraints], nodePolicyVersion: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(noheartbeat: Option[String]): Option[String] = {
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Some(ExchMsg.translate("bad.noheartbeat.param"))
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of strings", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        return Some(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
      }
    }
    if (deployment.isDefined) {
      for (p <- deployment.get.properties.getOrElse(List[OneProperty]())) {
        if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
          return Some(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
        }
      }
    }
    if (management.isDefined) {
      for (p <- management.get.properties.getOrElse(List[OneProperty]())) {
        if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
          return Some(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
        }
      }
    }
    None
  }

  def toNodePolicyRow(nodeId: String): NodePolicyRow = NodePolicyRow(nodeId, label.getOrElse(""), description.getOrElse(label.getOrElse("")), write(properties), write(constraints), write(deployment), write(management), nodePolicyVersion.getOrElse(""), ApiTime.nowUTC)
}


/** Output format for GET /orgs/{orgid}/nodes/{id}/agreements */
final case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/{id}/agreements/<agreement-id> */
final case class PutNodeAgreementRequest(services: Option[List[NAService]], agreementService: Option[NAgrService], state: String) {
  require(state!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(noheartbeat: Option[String]): Option[String] = {
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Some(ExchMsg.translate("bad.noheartbeat.param"))
    if (services.isEmpty && agreementService.isEmpty) {
      return Some(ExchMsg.translate("must.specify.service.or.agreementservice"))
    }
    None
  }

  def toNodeAgreementRow(nodeId: String, agId: String): NodeAgreementRow = {
    if (agreementService.isDefined) NodeAgreementRow(agId, nodeId, write(services), agreementService.get.orgid, agreementService.get.pattern, agreementService.get.url, state, ApiTime.nowUTC)
    else NodeAgreementRow(agId, nodeId, write(services), "", "", "", state, ApiTime.nowUTC)
  }
}


/** Input body for POST /orgs/{orgid}/nodes/{id}/msgs */
final case class PostNodesMsgsRequest(message: String, ttl: Int) {
  require(message!=null)
}

/** Response for GET /orgs/{orgid}/nodes/{id}/msgs */
final case class GetNodeMsgsResponse(messages: List[NodeMsg], lastIndex: Int)


/** Implementation for all of the /orgs/{orgid}/nodes routes */
@Path("/v1/orgs/{orgid}")
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
                           nodeDeleteRoute ~
                           nodeDeleteStatusRoute ~
                           nodeGetAgreementRoute ~
                           nodeGetAgreementsRoute ~
                           nodeGetErrorsRoute ~
                           nodeGetMsgRoute ~
                           nodeGetMsgsRoute ~
                           nodeGetPolicyRoute ~
                           nodeGetRoute ~
                           nodeGetStatusRoute ~
                           nodeGetManagementStatusRoute ~
                           nodeGetNMPStatusRoute ~
                           nodeDeleteNMPStatusRoute ~
                           nodeHeartbeatRoute ~
                           nodePatchRoute ~
                           nodePostConfigStateRoute ~
                           nodePostMsgRoute ~
                           nodePutAgreementRoute ~
                           nodePutErrorsRoute ~
                           nodePutPolicyRoute ~
                           nodePutStatusRoute ~
                           nodePutRoute ~
                           nodesGetDetails

  
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
      "lastUpdated": "string"
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
  def nodesGetRoute: Route = (path("orgs" / Segment / "nodes") & get & parameter("idfilter".?, "name".?, "owner".?, "arch".?, "nodetype".?)) { (orgid, idfilter, name, owner, arch, nodetype) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes")
    exchAuth(TNode(OrgAndId(orgid,"#").toString), Access.READ) { ident =>
      validateWithMsg(GetNodesUtils.getNodesProblem(nodetype)) {
        complete({
          logger.debug(s"GET /orgs/$orgid/nodes identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
          var q = NodesTQ.getAllNodes(orgid)
          idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
          name.foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })

          if (ident.isAdmin || ident.role.equals(AuthRoles.Agbot)) {
              owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          } else q = q.filter(_.owner === ident.identityString)

          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          arch.foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

          if (nodetype.isDefined) {
            val nt: String = nodetype.get.toLowerCase
            if (NodeType.isDevice(nt)) q = q.filter(r => {r.nodeType === nt || r.nodeType === ""})
            else if (NodeType.isCluster(nt)) q = q.filter(_.nodeType === nt)
          }

          db.run(q.result).map({ list =>
            logger.debug(s"GET /orgs/$orgid/nodes result size: ${list.size}")
            //val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
            val nodes: Map[String, Node] = list.map(e => e.id -> e.toNode(ident.isSuperUser)).toMap
            val code: StatusCode with Serializable = if (nodes.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetNodesResponse(nodes, 0))
          })
        }) // end of complete
      }
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
      "lastUpdated": "string"
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
  def nodeGetRoute: Route = (path("orgs" / Segment / "nodes" / Segment) & get & parameter("attribute".?)) { (orgid, id, attribute) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.READ) { ident =>
      val q = if (attribute.isDefined) NodesTQ.getAttribute(compositeId, attribute.get) else null
      validate(attribute.isEmpty || q!= null, ExchMsg.translate("node.name.not.in.resource")) {
        complete({
          attribute match {
            case Some(attr) =>  // Only returning 1 attr of the node
              val q = NodesTQ.getAttribute(compositeId, attr)
              if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("not.a.node.attribute", attr)))
              else db.run(q.result).map({ list =>
                logger.debug("GET /orgs/"+orgid+"/nodes/"+id+" attribute result: "+list.size)
                if (list.nonEmpty) (HttpCode.OK, GetNodeAttributeResponse(attr, list.head.toString))
                else(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))     // validateAccessToNode() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
              })

            case None =>   // Return the whole node
              val q = NodesTQ.getNode(compositeId)
              db.run(q.result).map({ list =>
                logger.debug("GET /orgs/"+orgid+"/nodes/"+id+" result: "+list.size)
                if (list.nonEmpty) {
                  //val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
                  val nodes: Map[String, Node] = list.map(e => e.id -> e.toNode(ident.isSuperUser)).toMap
                  (HttpCode.OK, GetNodesResponse(nodes, 0))
                } else {
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))     // validateAccessToNode() will return ApiRespType.NOT_FOUND to the client so do that here for consistency
                }
              })
          }
        }) // end of complete
      } // end of validate
    } // end of exchAuth
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
  }
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
  def nodePutRoute: Route = (path("orgs" / Segment / "nodes" / Segment) & put & parameter("noheartbeat".?) & entity(as[PutNodesRequest])) { (orgid, id, noheartbeat, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgid/nodes/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    var orgMaxNodes = 0
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem(id, noheartbeat)) {
        complete({
          val noHB = if (noheartbeat.isEmpty) false else if (noheartbeat.get.toLowerCase == "true") true else false
          var orgLimitMaxNodes = 0
          var fivePercentWarning = false
          var hashedPw = ""
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          val patValidateAction = if (reqBody.pattern != "") PatternsTQ.getPattern(reqBody.pattern).length.result else DBIO.successful(1)
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced in userInput exist
          db.run(patValidateAction.asTry.flatMap({
            case Success(num) =>
              // Check if pattern exists, then get services referenced
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " pattern validation: " + num)
              if (num > 0) valServiceIdActions.asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(v) =>
              // Check if referenced services exist, then get whether node is using policy
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " service validation: " + v)
              var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break()
                  }
                }
              }
              if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(compositeId).result.asTry
              else {
                val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(v) =>
              // Check if node is using policy, then get org limits
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " policy related attrs: " + v)
              if (v.nonEmpty) {
                val (existingPattern, existingPublicKey) = v.head
                if (reqBody.pattern != "" && existingPattern == "" && existingPublicKey != "") DBIO.failed(new Throwable(ExchMsg.translate("not.pattern.when.policy"))).asTry
                else OrgsTQ.getLimits(orgid).result.asTry // they are not trying to switch from policy to pattern, so we can continue
              }
              else OrgsTQ.getLimits(orgid).result.asTry // node doesn't exit yet, we can continue
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(orgLimits) =>
            // check total number of nodes
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " orgLimits: " + orgLimits)
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " orgLimits.head: " + orgLimits.head)
              val limits : OrgLimits = OrgLimits.toOrgLimit(orgLimits.head)
              orgLimitMaxNodes = limits.maxNodes
              orgMaxNodes = orgLimitMaxNodes
              NodesTQ.getAllNodes(orgid).length.result.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(totalNodes) =>
              // verify total nodes within org limit, then get numOwned
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " total number of nodes in org: " + totalNodes)
              if (orgLimitMaxNodes == 0) NodesTQ.getNumOwned(owner).result.asTry // no limit set
              else if (totalNodes >= orgLimitMaxNodes) DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.org.max.limit.of.nodes", totalNodes, orgLimitMaxNodes))).asTry
              else if ((orgLimitMaxNodes-totalNodes) <= orgLimitMaxNodes*.05) { // if we are within 5% of the limit
                fivePercentWarning = true // used for warning later
                NodesTQ.getNumOwned(owner).result.asTry
              } else NodesTQ.getNumOwned(owner).result.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(numOwned) =>
              // Check if num nodes owned is below limit, then create/update node
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " num owned: " + numOwned)
              val maxNodes: Int = ExchConfig.getInt("api.limits.maxNodes")
              if (maxNodes == 0
                  || numOwned <= maxNodes
                  || owner == "")  // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
                NodesTQ.getLastHeartbeat(compositeId).result.asTry
              else
                DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.nodes", maxNodes))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(lastHeartbeat) => 
              lastHeartbeat.size match {
                  case 0 => val lastHB = if (noHB) None else Some(ApiTime.nowUTC)
                    hashedPw = Password.fastHash(reqBody.token)
                    (if (owner == "")
                        // It seems like this case is an error (node doesn't exist yet, and client is not a user). The update will fail, but probably not with an error that will really explain what they did wrong.
                        reqBody.getDbUpdate(compositeId, orgid, owner, hashedPw, lastHB)
                      else 
                        reqBody.getDbUpsert(compositeId, orgid, owner, hashedPw, lastHB)).transactionally.asTry
                  case 1 => val lastHB = if (noHB) lastHeartbeat.head else Some(ApiTime.nowUTC)
                    hashedPw = Password.fastHash(reqBody.token)
                    (if (owner == "") 
                        reqBody.getDbUpdate(compositeId, orgid, owner, hashedPw, lastHB)
                      else 
                        reqBody.getDbUpsert(compositeId, orgid, owner, hashedPw, lastHB)).transactionally.asTry
                  case _ => DBIO.failed(new DBProcessingError(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.inserted.or.updated", compositeId, "Unexpected result"))).asTry
                }
            case Failure(t) => DBIO.failed(t).asTry
          })
          .flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " result: " + v)
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODE, ResChangeOperation.CREATEDMODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              // Check creation/update of node, and other errors
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              AuthCache.putNodeAndOwner(compositeId, hashedPw, reqBody.token, owner)
              if (fivePercentWarning) (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("num.nodes.near.org.limit", orgid, orgMaxNodes)))
              else (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.added.or.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getServerErrorMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
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
  }
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
  def nodePatchRoute: Route = (path("orgs" / Segment / "nodes" / Segment) & patch & entity(as[PatchNodesRequest])) { (orgid, id, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/nodes/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val hashedPw: String = if (reqBody.token.isDefined) Password.fastHash(reqBody.token.get) else "" // hash the token if that is what is being updated
          val (action, attrName) = reqBody.getDbUpdate(compositeId, hashedPw)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.note.attr.specified")))
          else {
            val patValidateAction = if (attrName == "pattern" && reqBody.pattern.get != "") PatternsTQ.getPattern(reqBody.pattern.get).length.result else DBIO.successful(1)
            val (valServiceIdActions, svcRefs) = if (attrName == "userInput") NodesTQ.validateServiceIds(reqBody.userInput.get) else (DBIO.successful(Vector()), Vector())
            db.run(patValidateAction.asTry.flatMap({
              case Success(num) =>
                // Check if pattern exists, then get services referenced
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " pattern validation: " + num)
                if (num > 0) valServiceIdActions.asTry
                else DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Check if referenced services exist, then get whether node is using policy
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " service validation: " + v)
                var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break()
                    }
                  }
                }
                if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(compositeId).result.asTry
                else {
                  val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Check if node is using policy, then update node
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " policy related attrs: " + v)
                if (v.nonEmpty) {
                  val (existingPattern, existingPublicKey) = v.head
                  if (reqBody.pattern.getOrElse("") != "" && existingPattern == "" && existingPublicKey != "") DBIO.failed(new Throwable(ExchMsg.translate("not.pattern.when.policy"))).asTry
                  else action.transactionally.asTry // they are not trying to switch from policy to pattern, so we can continue
                }
                else action.transactionally.asTry // node doesn't exit yet, we can continue
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " result: " + v)
                ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODE, ResChangeOperation.MODIFIED).insert.asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
                try {
                  val numUpdated: Int = v.toString.toInt // v comes to us as type Any
                  if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                    if (reqBody.token.isDefined) AuthCache.putNode(compositeId, hashedPw, reqBody.token.get) // We do not need to run putOwner because patch does not change the owner
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.attribute.updated", attrName, compositeId)))
                  } else {
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
                  }
                } catch {
                  case e: Exception => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("unexpected.result.from.update", e)))
                }
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))

            })
          }
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/nodes/{id}/services_configstate ===============================
  @POST
  @Path("nodes/{id}/services_configstate")
  @Operation(
    summary = "Changes config state of registered services",
    description = "Suspends (or resumes) 1 or more services on this edge node. Can be run by the node owner or the node.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
  def nodeDeleteRoute: Route = (path("orgs" / Segment / "nodes" / Segment) & delete) { (orgid, id) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/nodes/$id")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodesTQ.getNode(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/nodes/$id result: $v")
              AuthCache.removeNodeAndOwner(compositeId)
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODE, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug(s"DELETE /orgs/$orgid/nodes/$id updated in changes table: $v")
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

  // =========== POST /orgs/{orgid}/nodes/{id}/heartbeat ===============================
  @POST
  @Path("nodes/{id}/heartbeat")
  @Operation(summary = "Tells the exchange this node is still operating", description = "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
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

  /* ====== GET /orgs/{orgid}/nodes/{id}/errors ================================ */
  @GET
  @Path("nodes/{id}/errors")
  @Operation(summary = "Returns the node errors", description = "Returns any node errors. Can be run by any user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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

  // =========== PUT /orgs/{orgid}/nodes/{id}/errors ===============================
  @PUT
  @Path("nodes/{id}/errors")
  @Operation(
    summary = "Adds/updates node error list",
    description = "Adds or updates any error of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEERRORS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/errors ===============================
  @DELETE
  @Path("nodes/{id}/errors")
  @Operation(summary = "Deletes the error list of a node", description = "Deletes the error list of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEERRORS, ResChangeOperation.DELETED).insert.asTry
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
  /* ====== GET /orgs/{orgid}/nodes/{id}/status ================================ */
  @GET
  @Path("nodes/{id}/status")
  @Operation(summary = "Returns the node status", description = "Returns the node run time status, for example service container status. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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

  // =========== PUT /orgs/{orgid}/nodes/{id}/status ===============================
  @PUT
  @Path("nodes/{id}/status")
  @Operation(
    summary = "Adds/updates the node status",
    description = "Adds or updates the run time status of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODESTATUS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/status ===============================
  @DELETE
  @Path("nodes/{id}/status")
  @Operation(summary = "Deletes the status of a node", description = "Deletes the status of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODESTATUS, ResChangeOperation.DELETED).insert.asTry
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

  /* ====== GET /orgs/{orgid}/nodes/{id}/policy ================================ */
  @GET
  @Path("nodes/{id}/policy")
  @Operation(summary = "Returns the node policy", description = "Returns the node run time policy. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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

  // =========== PUT /orgs/{orgid}/nodes/{id}/policy ===============================
  @PUT
  @Path("nodes/{id}/policy")
  @Operation(
    summary = "Adds/updates the node policy",
    description = "Adds or updates the policy of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
                  ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEPOLICIES, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/policy ===============================
  @DELETE
  @Path("nodes/{id}/policy")
  @Operation(summary = "Deletes the policy of a node", description = "Deletes the policy of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEPOLICIES, ResChangeOperation.DELETED).insert.asTry
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

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements ================================ */
  @GET
  @Path("nodes/{id}/agreements")
  @Operation(summary = "Returns all agreements this node is in", description = "Returns all agreements that this node is part of. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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
          val code: StatusCode with Serializable = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements/{agid} ================================ */
  @GET
  @Path("nodes/{id}/agreements/{agid}")
  @Operation(summary = "Returns an agreement for a node", description = "Returns the agreement with the specified agid for the specified node id. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
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
          val code: StatusCode with Serializable = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  @PUT
  @Path("nodes/{id}/agreements/{agid}")
  @Operation(
    summary = "Adds/updates an agreement of a node",
    description = "Adds a new agreement of a node, or updates an existing agreement. This is called by the node or owning user to give their information about the agreement.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements ===============================
  @DELETE
  @Path("nodes/{id}/agreements")
  @Operation(summary = "Deletes all agreements of a node", description = "Deletes all of the current agreements of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  @DELETE
  @Path("nodes/{id}/agreements/{agid}")
  @Operation(summary = "Deletes an agreement of a node", description = "Deletes an agreement of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
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
              ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEAGREEMENTS, ResChangeOperation.DELETED).insert.asTry
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

  // =========== POST /orgs/{orgid}/nodes/{id}/msgs ===============================
  @POST
  @Path("nodes/{id}/msgs")
  @Operation(
    summary = "Sends a msg from an agbot to a node",
    description = "Sends a msg from an agbot to a node. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the node's public key). Can be run by any agbot.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "id",
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
            ResourceChange(0L, orgid, id, ResChangeCategory.NODE, false, ResChangeResource.NODEMSGS, ResChangeOperation.CREATED).insert.asTry
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

  /* ====== GET /orgs/{orgid}/nodes/{id}/msgs ================================ */
  @GET
  @Path("nodes/{id}/msgs")
  @Operation(summary = "Returns all msgs sent to this node", description = "Returns all msgs that have been sent to this node. They will be returned in the order they were sent. All msgs that have been sent to this node will be returned, unless the node has deleted some, or some are past their TTL. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
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
            val code: StatusCode with Serializable = if (msgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetNodeMsgsResponse(msgs, 0))
          })
        }) // end of complete
      }
    } // end of exchAuth
  }
  
  /* ====== GET /orgs/{orgid}/nodes/{id}/msgs/{msgid} ================================ */
  @GET
  @Path("nodes/{id}/msgs/{msgId}")
  @Operation(description = "Returns A specific message that has been sent to this node. Deleted/post-TTL (Time To Live) messages will not be returned. Can be run by a user or the node.",
             parameters = Array(new Parameter(description = "ID of the node.",
                                              in = ParameterIn.PATH,
                                              name = "id",
                                              required = true),
                                new Parameter(description = "Specific node message.",
                                              in = ParameterIn.PATH,
                                              name = "msgid",
                                              required = true),
                                new Parameter(description = "Organization id.",
                                              in = ParameterIn.PATH,
                                              name = "orgid",
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

  // =========== DELETE /orgs/{orgid}/nodes/{id}/msgs/{msgid} ===============================
  @DELETE
  @Path("nodes/{id}/msgs/{msgid}")
  @Operation(summary = "Deletes a msg of an node", description = "Deletes a message that was sent to an node. This should be done by the node after each msg is read. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
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
  
  // ====== GET /orgs/{orgid}/node-details ===================================
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
        "serviceVersionRange": "string"
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
                    nodes <- NodesTQ.rows
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
                                   .joinLeft(NodeErrorTQ.rows.filterOpt(id)((nodeErrors, id) => nodeErrors.nodeId like id))
                                     .on(_.id === _.nodeId)
                                   .joinLeft(NodePolicyTQ.rows.filterOpt(id)((nodePolicy, id) => nodePolicy.nodeId like id))
                                     .on(_._1.id === _.nodeId)
                                   .joinLeft(NodeStatusTQ.rows.filterOpt(id)((nodeStatuses, id) => nodeStatuses.nodeId like id))
                                     .on(_._1._1.id === _.nodeId) // node.id === nodeStatus.nodeid
                                   .sortBy(_._1._1._1.id.asc)     // node.id ASC
                                   // (((Nodes, Node Errors), Node Policy), Node Statuses)
                                   // Flatten the tupled structure, lexically sort columns.
                                   .map(
                                     node =>
                                       (node._1._1._1.arch,
                                         node._1._1._1.id,
                                         node._1._1._1.heartbeatIntervals,
                                         node._1._1._1.lastHeartbeat,
                                         node._1._1._1.lastUpdated,
                                         node._1._1._1.msgEndPoint,
                                         node._1._1._1.name,
                                         node._1._1._1.nodeType,
                                         node._1._1._1.orgid,
                                         node._1._1._1.owner,
                                         node._1._1._1.pattern,
                                         node._1._1._1.publicKey,
                                         node._1._1._1.regServices,
                                         node._1._1._1.softwareVersions,
                                         (if(ident.isAdmin ||
                                             ident.isSuperUser) // Do not pull nor query the Node's token if (Super)Admin.
                                           node._1._1._1.id.substring(0,0) // node.id -> ""
                                         else
                                           node._1._1._1.token),
                                         node._1._1._1.userInput,
                                         node._1._1._2,           // Node Errors (errors, lastUpdated)
                                         node._1._2,              // Node Policy (constraints, lastUpdated, properties)
                                         node._2))                // Node Statuses (connectivity, lastUpdated, runningServices, services)
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
                                               Some(node._1),
                                             connectivity =
                                               if(node._19.isEmpty ||
                                                  node._19.get.connectivity.isEmpty)
                                                 None
                                               else
                                                 Some(read[Map[String, Boolean]](node._19.get.connectivity)),
                                             constraints =
                                              if(node._18.isEmpty ||
                                                 node._18.get.constraints.isEmpty)
                                                None
                                              else
                                                Some(read[List[String]](node._18.get.constraints)),
                                             errors =
                                               if(node._17.isEmpty ||
                                                  node._17.get.errors.isEmpty)
                                                 None
                                               else
                                                 Some(read[List[Any]](node._17.get.errors)),
                                             id = node._2,
                                             heartbeatIntervals =
                                               if(node._3.isEmpty)
                                                 Some(NodeHeartbeatIntervals(0, 0, 0))
                                               else
                                                 Some(read[NodeHeartbeatIntervals](node._3)),
                                             lastHeartbeat = node._4,
                                             lastUpdatedNode = node._5,
                                             lastUpdatedNodeError =
                                               if(node._17.isDefined)
                                                 Some(node._17.get.lastUpdated)
                                               else
                                                 None,
                                             lastUpdatedNodePolicy =
                                              if(node._18.isDefined)
                                                Some(node._18.get.lastUpdated)
                                              else
                                                None,
                                             lastUpdatedNodeStatus =
                                               if(node._19.isDefined)
                                                 Some(node._19.get.lastUpdated)
                                               else
                                                 None,
                                             msgEndPoint =
                                               if(node._6.isEmpty)
                                                 None
                                               else
                                                 Some(node._6),
                                             name =
                                               if(node._7.isEmpty)
                                                 None
                                               else
                                                 Some(node._7),
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
                                                 Some(node._11),
                                             properties =
                                              if(node._18.isEmpty ||
                                                 node._18.get.properties.isEmpty)
                                                None
                                              else
                                                Some(read[List[OneProperty]](node._18.get.properties)),
                                             publicKey =
                                               if(node._12.isEmpty)
                                                 None
                                               else
                                                 Some(node._12),
                                             registeredServices =
                                               if(node._13.isEmpty)
                                                 None
                                               else
                                                 Some(read[List[RegService]](node._13).map(rs => RegService(rs.url, rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties, rs.version))),
                                             runningServices =
                                               if(node._19.isEmpty ||
                                                  node._19.get.services.isEmpty)
                                                 None
                                               else
                                                 Some(node._19.get.runningServices),
                                             services =
                                               if(node._19.isEmpty ||
                                                  node._19.get.services.isEmpty)
                                                 None
                                               else
                                                 Some(read[List[OneService]](node._19.get.services)),
                                             softwareVersions =
                                               if(node._14.isEmpty)
                                                 None
                                               else
                                                 Some(read[Map[String, String]](node._14)),
                                             token =
                                               if(node._15.isEmpty)
                                                 StrConstants.hiddenPw
                                               else
                                                 node._15,
                                             userInput =
                                               if(node._16.isEmpty)
                                                 None
                                               else
                                                 Some(read[List[OneUserInputService]](node._16)))).toList)
                    
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


  /* ====== GET /orgs/{orgid}/nodes/{id}/managementStatus ================================ */
  @GET
  @Path("/v1/orgs/{orgid}/nodes/{id}/managementStatus")
  @Operation(summary = "Returns status for nodeid", description = "Returns the management status of the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned")),
      //new Parameter(name = "nmpid", in = ParameterIn.PATH, description = "ID of the node management policy."),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
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
            schema = new Schema(implementation = classOf[NodeMgmtPolStatusRow])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/managementStatus")
  def nodeGetManagementStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus") & get) { (orgid, id) =>
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { ident =>
      complete({
        var q = NodeMgmtPolStatuses.getAllNMPStatus(orgid + "/" + id)
        db.run(q.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/managementStatus result size: "+list.size)
          val nmpStatuses: Map[String, NMPStatus] = list.map(e => e.policy -> e.toNodeMgmtPolStatus).toMap //mapping management policy object to string
          val code: StatusCode with Serializable = if (nmpStatuses.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNMPStatusResponse(nmpStatuses, 0))
        })
      }) // end of complete
    } // end of validate
  } // end of exchAuth


  /* ====== GET /orgs/{orgid}/nodes/{id}/managementStatus/{nmpID} ================================ */
  @GET
  @Path("/v1/orgs/{orgid}/nodes/{id}/managementStatus/{nmpID}")
  @Operation(summary = "Returns status for nodeid", description = "Returns the management status of the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned"),
      new Parameter(name = "nmpid", in = ParameterIn.PATH, description = "ID of the node management policy.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "nmpID": [
    {
       "agentUpgrade": {
            "scheduledTime":  "<RFC3339 timestamp>", // the scheduled time to start the upgrade
            "startTime":  "<RFC3339 timestamp>", // the actual time the upgrade process started.
            "endTime":  "<RFC3339 timestamp>", // the time the upgrade process ended.
            "upgradeType":  "",   // the type of the upgrade, like configOnly etc.
            "upgradedVersion": "<version>",    // the version that has been upgraded to.
            "status":  "",   // the status for the upgrade process. It can be success, failed, in progress etc.
            "errorMessage": "",   // the error message if the upgrade process failed.
       }
     }
  ],
  ...
  "lastUpdated": ""
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[NodeMgmtPolStatusRow])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/managementStatus")
  def nodeGetNMPStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) & get) { (orgid, id, nmpid) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes/$id/managementStatus/$nmpid")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeMgmtPolStatuses.getNodeMgmtPolStatus(orgid + "/" + id, orgid + "/" + nmpid).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/managementStatus/$nmpid status result size: ${list.size}")
          val nmpStatuses: Map[String, NMPStatus] = list.map(e => e.policy -> e.toNodeMgmtPolStatus).toMap //mapping management policy object to string
          val code: StatusCode with Serializable = if (nmpStatuses.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNMPStatusResponse(nmpStatuses, 0))
//          if (list.nonEmpty) (HttpCode.OK, list.head.toNodeMgmtPolStatus)
//          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
            //if (list.head != "") read[AgentUpgradePolicy](list.head) else AgentUpgradePolicy("current","now",0))

        })
      }) // end of complete
    } // end of validate
  } // end of exchAuth

  // =========== DELETE /orgs/{orgid}/nodes/{id} ===============================
  @DELETE
  @Path("/v1/orgs/{orgid}/nodes/{id}/managementStatus/{nmpID}")
  @Operation(summary = "Deletes a node management policy status", description = "Deletes a node management policy status, Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "nmpid", in = ParameterIn.PATH, description = "ID of the node management policy.")),
  responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "node/managementStatus")
  def nodeDeleteNMPStatusRoute: Route = (path("orgs" / Segment / "nodes" / Segment / "managementStatus" / Segment) & delete) { (orgid, id, nmpid) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/nodes/$id/managementStatus/$nmpid")
    val compositeId: String = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(ManagementPoliciesTQ.getManagementPolicy(nmpid).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/nodes/$id/managementStatus/$nmpid result: $v")
              AuthCache.removeManagementPolicyOwner(compositeId)
              AuthCache.removeManagementPolicyIsPublic(compositeId)
              ResourceChange(0L, orgid, id, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("management.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug(s"DELETE /orgs/$orgid/nodes/$id/managementStatus/$nmpid updated in changes table: $v")
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("management.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (t.getMessage.contains("couldn't find mgmtpolicy")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("management.policy.not.found", compositeId)))
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("management.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            if (t.getMessage.contains("couldn't find mgmtpolicy")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("management.policy.not.found", compositeId)))
            else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("management.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }
}
