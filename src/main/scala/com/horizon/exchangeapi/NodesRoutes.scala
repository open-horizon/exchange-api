/** Services routes for all of the /orgs/{orgid}/nodes api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext.Implicits.global

//import com.horizon.exchangeapi.auth.{AuthException, DBProcessingError}
import com.horizon.exchangeapi.tables._
import org.json4s._
//import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{read, write}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
//import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/nodes routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/nodes */
final case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int)
final case class GetNodeAttributeResponse(attribute: String, value: String)

// Tried this to have names on the tuple returned from the db, but didn't work...
final case class PatternSearchHashElement(msgEndPoint: String, publicKey: String, noAgreementYet: Boolean)

final case class PatternNodeResponse(id: String, msgEndPoint: String, publicKey: String)
final case class PostPatternSearchResponse(nodes: List[PatternNodeResponse], lastIndex: Int)

// Leaving this here for the UI wanting to implement filtering later
final case class PostNodeErrorRequest() {
  def getAnyProblem: Option[String] = None
}
final case class PostNodeErrorResponse(nodes: scala.Seq[String])

final case class PostServiceSearchRequest(orgid: String, serviceURL: String, serviceVersion: String, serviceArch: String) {
  require(orgid!=null && serviceURL!=null && serviceVersion!=null && serviceArch!=null)
  def getAnyProblem: Option[String] = None
}
final case class PostServiceSearchResponse(nodes: scala.collection.Seq[(String, String)])

final case class NodeResponse(id: String, name: String, services: List[RegService], userInput: List[OneUserInputService], msgEndPoint: String, publicKey: String, arch: String)
final case class PostSearchNodesResponse(nodes: List[NodeResponse], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/<node-id> */
final case class PutNodesRequest(token: String, name: String, pattern: String, registeredServices: Option[List[RegService]], userInput: Option[List[OneUserInputService]], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: String, arch: Option[String]) {
  require(token!=null && name!=null && pattern!=null && publicKey!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  /** Halts the request with an error msg if the user input is invalid. */
  def getAnyProblem: Option[String] = {
    if (token == "") return Some(ExchMsg.translate("token.must.not.be.blank"))
    // if (publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    if (pattern != "" && """.*/.*""".r.findFirstIn(pattern).isEmpty) return Some(ExchMsg.translate("pattern.must.have.orgid.prepended"))
    for (m <- registeredServices.getOrElse(List())) {
      // now we support more than 1 agreement for a MS
      // if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => return Some(s)
        case None => ;
      }
    }
    return None
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { NodesTQ.validateServiceIds(userInput.getOrElse(List())) }

  /** Get the db actions to insert or update all parts of the node */
  def getDbUpsert(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2 = registeredServices.getOrElse(List()).map(rs => RegService(rs.url,rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties))
    NodeRow(id, orgid, hashedTok, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).upsert
  }

  /** Get the db actions to update all parts of the node. This is run, instead of getDbUpsert(), when it is a node doing it,
   * because we can't let a node create new nodes. */
  def getDbUpdate(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2 = registeredServices.getOrElse(List()).map(rs => RegService(rs.url,rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties))
    NodeRow(id, orgid, hashedTok, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).update
  }
}

final case class PatchNodesRequest(token: Option[String], name: Option[String], pattern: Option[String], registeredServices: Option[List[RegService]], userInput: Option[List[OneUserInputService]], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: Option[String], arch: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if (token.isDefined && token.get == "") Some(ExchMsg.translate("token.cannot.be.empty.string"))
    //else if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else None
  }
    /** Returns a tuple of the db action to update parts of the node, and the attribute name being updated. */
  def getDbUpdate(id: String, hashedPw: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //someday: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this node
    var dbAction: (DBIO[_], String) = (null, null)
    if(token.isEmpty && softwareVersions.isDefined && registeredServices.isDefined && name.isDefined && pattern.isDefined && userInput.isDefined && msgEndPoint.isDefined && publicKey.isDefined && arch.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions, d.regServices, d.name, d.pattern, d.userInput, d.msgEndPoint, d.publicKey, d.arch, d.lastHeartbeat)).update((id, write(softwareVersions), write(registeredServices), name.get, pattern.get, write(userInput), msgEndPoint.get, publicKey.get, arch.get, lastHeartbeat)), "update all but token")
    } else if (token.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, hashedPw, lastHeartbeat)), "token")
    } else if (softwareVersions.isDefined){
      val swVersions = if (softwareVersions.nonEmpty) write(softwareVersions) else ""
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat)).update((id, swVersions, lastHeartbeat)), "softwareVersions")
    } else if (registeredServices.isDefined){
      val regSvc = if (registeredServices.nonEmpty) write(registeredServices) else ""
      dbAction =  ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat)).update((id, regSvc, lastHeartbeat)), "registeredServices")
    } else if (name.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name.get, lastHeartbeat)), "name")
    } else if (pattern.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.pattern,d.lastHeartbeat)).update((id, pattern.get, lastHeartbeat)), "pattern")
    } else if (userInput.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.userInput,d.lastHeartbeat)).update((id, write(userInput), lastHeartbeat)), "userInput")
    } else if (msgEndPoint.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint.get, lastHeartbeat)), "msgEndPoint")
    } else if (publicKey.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey.get, lastHeartbeat)), "publicKey")
    } else if (arch.isDefined){
      dbAction = ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.arch,d.lastHeartbeat)).update((id, arch.get, lastHeartbeat)), "arch")
    }
    return dbAction
  }
}

final case class PostNodeConfigStateRequest(org: String, url: String, configState: String) {
  require(org!=null && url!=null && configState!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  //def logger: Logger    // get access to the logger object in ExchangeApiApp

  def getAnyProblem: Option[String] = {
    if (configState != "suspended" && configState != "active") Some(ExchMsg.translate("configstate.must.be.suspended.or.active"))
    else None
  }

  // Match registered service urls (which are org/url) to the input org and url
  def isMatch(compositeUrl: String): Boolean = {
    val reg = """^(\S+?)/(\S+)$""".r
    val (comporg, compurl) = compositeUrl match {
      case reg(o,u) => (o, u)
      case _ => return false   //todo: halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("configstate.must.be.suspended.or.active", compositeUrl)))
    }
    (org, url) match {
      case ("","") => return true
      case ("",u) => return compurl == u
      case (o,"") => return comporg == o
      case (o,u) => return comporg == o && compurl == u
    }
  }

  // Given the existing list of registered svcs in the db for this node, determine the db update necessary to apply the new configState
  def getDbUpdate(regServices: String, id: String): DBIO[_] = {
    if (regServices == "") return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))
    val regSvcs = read[List[RegService]](regServices)
    if (regSvcs.isEmpty) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("node.has.no.services")))

    // Copy the list of required svcs, changing configState wherever it applies
    var matchingSvcFound = false
    val newRegSvcs = regSvcs.map({ rs =>
      if (isMatch(rs.url)) {
        matchingSvcFound = true   // warning: intentional side effect (didnt know how else to do it)
        if (configState != rs.configState.getOrElse("")) RegService(rs.url,rs.numAgreements, Some(configState), rs.policy, rs.properties)
        else rs
      }
      else rs
    })
    // this check is not ok, because we should not return NOT_FOUND if we find matching svc but their configState is already set the requested value
    //if (newRegSvcs.sameElements(regSvcs)) halt(HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, "did not find any registeredServices that matched the given org and url criteria."))
    if (!matchingSvcFound) return DBIO.failed(new ResourceNotFoundException(ExchMsg.translate("did.not.find.registered.services")))
    if (newRegSvcs == regSvcs) {
      println(ExchMsg.translate("no.db.update.necessary"))
      //logger.debug("No db update necessary, all relevant config states already correct")
      return DBIO.successful(1)    // all the configStates were already set correctly, so nothing to do
    }

    // Convert from struct back to string and return db action to update that
    val newRegSvcsString = write(newRegSvcs)
    return (for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat)).update((id, newRegSvcsString, ApiTime.nowUTC))
  }
}

final case class PutNodeStatusRequest(connectivity: Map[String,Boolean], services: List[OneService]) {
  require(connectivity!=null && services!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None
  var runningServices = "|"
  for(s <- services){
    runningServices = runningServices + s.orgid + "/" + s.serviceUrl + "_" + s.version + "_" + s.arch + "|"
  }
  def toNodeStatusRow(nodeId: String) = NodeStatusRow(nodeId, write(connectivity), write(services), runningServices, ApiTime.nowUTC)
}

final case class PutNodeErrorRequest(errors: List[Any]) {
  require(errors!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None

  def toNodeErrorRow(nodeId: String) = NodeErrorRow(nodeId, write(errors), ApiTime.nowUTC)
}

final case class PutNodePolicyRequest(properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of string", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        return Some(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
      }
    }
    return None
  }

  def toNodePolicyRow(nodeId: String) = NodePolicyRow(nodeId, write(properties), write(constraints), ApiTime.nowUTC)
}


/** Output format for GET /orgs/{orgid}/nodes/{id}/agreements */
final case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/{id}/agreements/<agreement-id> */
final case class PutNodeAgreementRequest(services: Option[List[NAService]], agreementService: Option[NAgrService], state: String) {
  require(state!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    if (services.isEmpty && agreementService.isEmpty) {
      return Some(ExchMsg.translate("must.specify.service.or.agreementservice"))
    }
    return None
  }

  def toNodeAgreementRow(nodeId: String, agId: String) = {
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
@Path("/v1/orgs/{orgid}/nodes")
class NodesRoutes(implicit val system: ActorSystem) extends JacksonSupport with AuthenticationSupport {
  def db: Database = ExchangeApiApp.getDb
  lazy implicit val logger: LoggingAdapter = Logging(system, classOf[OrgsRoutes])
  //protected implicit def jsonFormats: Formats
  // implicit def formats: org.json4s.Formats{val dateFormat: org.json4s.DateFormat; val typeHints: org.json4s.TypeHints}

  def routes: Route = nodesGetRoute ~ nodeGetRoute ~ nodePutRoute ~ nodePatchRoute ~ nodePostConfigStateRoute ~ nodeDeleteRoute ~ nodeHeartbeatRoute ~ nodeGetErrorsRoute ~ nodePutErrorsRoute ~ nodeDeleteErrorsRoute ~ nodeGetStatusRoute ~ nodePutStatusRoute ~ nodeDeleteStatusRoute ~ nodeGetPolicyRoute ~ nodePutPolicyRoute ~ nodeDeletePolicyRoute ~ nodeGetAgreementsRoute ~ nodeGetAgreementRoute ~ nodePutAgreementRoute ~ nodeDeleteAgreementsRoute ~ nodeDeleteAgreementRoute ~ nodePostMsgRoute ~ nodeGetMsgsRoute ~ nodeDeleteMsgRoute

  // ====== GET /orgs/{orgid}/nodes ================================
  @GET
  @Path("")
  @Operation(summary = "Returns all nodes", description = "Returns all nodes (edge devices). Can be run by any user or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "name", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this name (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this owner (can include % for wildcard - the URL encoding for % is %25)"),
    new Parameter(name = "arch", in = ParameterIn.QUERY, required = false, description = "Filter results to only include nodes with this arch (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetNodesResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodesGetRoute: Route = (get & path("orgs" / Segment / "nodes") & parameter(('idfilter.?, 'name.?, 'owner.?, 'arch.?))) { (orgid, idfilter, name, owner, arch) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes")
    exchAuth(TNode(OrgAndId(orgid,"*").toString), Access.READ) { ident =>
      complete({
        logger.debug(s"GET /orgs/$orgid/nodes identity: $ident")
        var q = NodesTQ.getAllNodes(orgid)
        idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
        name.foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
        owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
        arch.foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

        db.run(q.result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes result size: ${list.size}")
          val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
          val code = if (nodes.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodesResponse(nodes, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id} ================================ */
  @GET
  @Path("{id}")
  @Operation(summary = "Returns a node", description = "Returns the node (edge device) with the specified id. Can be run by that node, a user, or an agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetNodesResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment) & parameter(('attribute.?))) { (orgid, id, attribute) =>
    logger.debug(s"Doing GET /orgs/$orgid/nodes/$id")
    val compositeId = OrgAndId(orgid,id).toString
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
                  val nodes = NodesTQ.parseJoin(ident.isSuperUser, list)
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
  @Path("{id}")
  @Operation(summary = "Add/updates a node", description = "Adds a new edge node, or updates an existing node. This must be called by the user to add a node, and then can be called by that user or node to update itself.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    requestBody = new RequestBody(description = """
```
{
  "token": "abc",       // node token, set by user when adding this node.
  "name": "rpi3",         // node name that you pick
  "pattern": "myorg/mypattern",      // (optional) points to a pattern resource that defines what services should be deployed to this type of node
  "arch": "arm",      // specifies the architecture of the node
  "registeredServices": [    // list of data services you want to make available
    {
      "url": "IBM/github.com.open-horizon.examples.cpu",
      "numAgreements": 1,       // for now always set this to 1
      "policy": "{...}"     // the service policy file content as a json string blob
      "properties": [    // list of properties to help agbots search for this, or requirements on the agbot
        {
          "name": "arch",         // must at least include arch and version properties
          "value": "arm",         // should always be a string (even for boolean and int). Use "*" for wildcard
          "propType": "string",   // valid types: string, list, version, boolean, int, or wildcard
          "op": "="               // =, greater-than-or-equal-symbols, less-than-or-equal-symbols, or in (must use the same op as the agbot search)
        }
      ]
    }
  ],
  // Override or set user input variables that are defined in the services used by this node.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",        // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",   // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "msgEndPoint": "",    // not currently used, but may be in the future. Leave empty or omit to use the built-in Exchange msg service
  "softwareVersions": {"horizon": "1.2.3"},      // various software versions on the node, can omit
  "publicKey": "ABCDEF"      // used by agbots to encrypt msgs sent to this node using the built-in Exchange msg service
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutNodesRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource add/updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePutRoute: Route = (put & path("orgs" / Segment / "nodes" / Segment) & entity(as[PutNodesRequest])) { (orgid, id, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgid/nodes/$id")
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { ident =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
          val patValidateAction = if (reqBody.pattern != "") PatternsTQ.getPattern(reqBody.pattern).length.result else DBIO.successful(1)
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced in userInput exist
          val hashedTok = Password.hash(reqBody.token)
          db.run(patValidateAction.asTry.flatMap({
            case Success(num) =>
              // Check if pattern exists, then get services referenced
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " pattern validation: " + num)
              if (num > 0) valServiceIdActions.asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("pattern.not.in.exchange"))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Check if referenced services exist, then get whether node is using policy
              logger.debug("PUT /orgs/" + orgid + "/nodes" + id + " service validation: " + v)
              var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break
                  }
                }
              }
              if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(compositeId).result.asTry
              else {
                val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Check if node is using policy, then get num nodes already owned
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " policy related attrs: " + v)
              if (v.nonEmpty) {
                val (existingPattern, existingPublicKey) = v.head
                if (reqBody.pattern != "" && existingPattern == "" && existingPublicKey != "") DBIO.failed(new Throwable(ExchMsg.translate("not.pattern.when.policy"))).asTry
                else NodesTQ.getNumOwned(owner).result.asTry // they are not trying to switch from policy to pattern, so we can continue
              }
              else NodesTQ.getNumOwned(owner).result.asTry // node doesn't exit yet, we can continue
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(numOwned) =>
              // Check if num nodes owned is below limit, then create/update node
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " num owned: " + numOwned)
              val maxNodes = ExchConfig.getInt("api.limits.maxNodes")
              if (maxNodes == 0 || numOwned <= maxNodes || owner == "") { // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
                val action = if (owner == "") reqBody.getDbUpdate(compositeId, orgid, owner, hashedTok) else reqBody.getDbUpsert(compositeId, orgid, owner, hashedTok)
                action.transactionally.asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.of.nodes", maxNodes))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " result: " + v)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "node", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              // Check creation/update of node, and other errors
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              AuthCache.putNodeAndOwner(compositeId, hashedTok, reqBody.token, owner)
              //AuthCache.ids.putNode(id, hashedTok, node.token)
              //AuthCache.nodesOwner.putOne(id, owner)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.added.or.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{orgid}/nodes/{id} ===============================
  @PATCH
  @Path("{id}")
  @Operation(summary = "Updates 1 attribute of a node", description = "Updates some attributes of a node. This can be called by the user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes (see list of attributes in the PUT route)", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PatchNodesRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePatchRoute: Route = (patch & path("orgs" / Segment / "nodes" / Segment) & entity(as[PatchNodesRequest])) { (orgid, id, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/nodes/$id")
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          val hashedPw = if (reqBody.token.isDefined) Password.hash(reqBody.token.get) else "" // hash the token if that is what is being updated
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
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(v) =>
                // Check if referenced services exist, then get whether node is using policy
                logger.debug("PATCH /orgs/" + orgid + "/nodes" + id + " service validation: " + v)
                var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break
                    }
                  }
                }
                if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(compositeId).result.asTry
                else {
                  val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
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
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " result: " + v)
                val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "node", ResourceChangeConfig.MODIFIED, ApiTime.nowUTC)
                nodeChange.insert.asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
                try {
                  val numUpdated = v.toString.toInt // v comes to us as type Any
                  if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                    if (reqBody.token.isDefined) AuthCache.putNode(compositeId, hashedPw, reqBody.token.get) // We do not need to run putOwner because patch does not change the owner
                    //AuthCache.ids.putNode(id, hashedPw, node.token.get)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.attribute.updated", attrName, compositeId)))
                  } else {
                    (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
                  }
                } catch {
                  case e: Exception => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("unexpected.result.from.update", e)))
                }
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))
            })
          }
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/nodes/{id}/services_configstate ===============================
  @POST
  @Path("{id}/services_configstate")
  @Operation(summary = "Changes config state of registered services", description = "Suspends (or resumes) 1 or more services on this edge node. Can be run by the node owner or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    requestBody = new RequestBody(description = """
```
{
  "org": "myorg",    // the org of services to be modified, or empty string for all orgs
  "url": "myserviceurl"       // the url of services to be modified, or empty string for all urls
  "configState": "suspended"   // or "active"
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostNodeConfigStateRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePostConfigStateRoute: Route = (post & path("orgs" / Segment / "nodes" / Segment / "services_configstate") & entity(as[PostNodeConfigStateRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
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
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "services_configstate", ResourceChangeConfig.CREATED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + n)
              if (n.asInstanceOf[Int] > 0) (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.services.updated", compositeId))) // there were no db errors, but determine if it actually found it or not
              else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            case Failure(t: AuthException) => t.toComplete
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("node.not.inserted.or.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id} ===============================
  @DELETE
  @Path("{id}")
  @Operation(summary = "Deletes a node", description = "Deletes a node (RPi), and deletes the agreements stored for this node (but does not actually cancel the agreements between the node and agbots). Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeDeleteRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment)) { (orgid, id) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/nodes/$id")
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodesTQ.getNode(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"DELETE /orgs/$orgid/nodes/$id result: $v")
              AuthCache.removeNodeAndOwner(compositeId)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "node", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
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
          case Failure(t) =>
            if (t.getMessage.contains("couldn't find node")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/nodes/{id}/heartbeat ===============================
  @POST
  @Path("{id}/heartbeat")
  @Operation(summary = "Tells the exchange this node is still operating", description = "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeHeartbeatRoute: Route = (post & path("orgs" / Segment / "nodes" / Segment / "heartbeat")) { (orgid, id) =>
    logger.debug(s"Doing POST /orgs/$orgid/users/$id/heartbeat")
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      complete({
        db.run(NodesTQ.getLastHeartbeat(compositeId).update(ApiTime.nowUTC).asTry).map({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              logger.debug(s"POST /orgs/$orgid/users/$id/heartbeat result: $v")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.updated")))
            } else {
              (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.not.found", compositeId)))
            }
          case Failure(t) => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.not.updated", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/errors ================================ */
  @GET
  @Path("{id}/errors")
  @Operation(summary = "Returns the node errors", description = "Returns any node errors. Can be run by any user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[NodeError])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetErrorsRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "errors")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
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
  @Path("{id}/errors")
  @Operation(summary = "Adds/updates node error list", description = "Adds or updates any error of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    requestBody = new RequestBody(description = """
```
{
  errors: [
    {
      record_id: "string",
      message: "string",
      event_code: "string",
      hidden: boolean
    },
    ...
  ]
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutNodeErrorRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePutErrorsRoute: Route = (put & path("orgs" / Segment / "nodes" / Segment / "errors") & entity(as[PutNodeErrorRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          db.run(reqBody.toNodeErrorRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/errors result: " + v)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodeerrors", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.errors.added")))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.errors.not.inserted", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.inserted", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/errors ===============================
  @DELETE
  @Path("{id}/errors")
  @Operation(summary = "Deletes the error list of a node", description = "Deletes the error list of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeDeleteErrorsRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "errors")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeErrorTQ.getNodeError(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/errors result: " + v)
            if (v > 0) {
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodeerrors", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
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
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.errors.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/status ================================ */
  @GET
  @Path("{id}/status")
  @Operation(summary = "Returns the node status", description = "Returns the node run time status, for example service container status. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[NodeStatus])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetStatusRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "status")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeStatusTQ.getNodeStatus(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/status result size: "+list.size)
          if (list.nonEmpty) (HttpCode.OK, list.head.toNodeStatus)
          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/nodes/{id}/status ===============================
  @PUT
  @Path("{id}/status")
  @Operation(summary = "Adds/updates the node status", description = "Adds or updates the run time status of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    requestBody = new RequestBody(description = """
```
{
  "connectivity": {
     "firmware.bluehorizon.network": true,
      "images.bluehorizon.network": true
   },
  "services": [
    {
      "agreementId": "78d7912aafb6c11b7a776f77d958519a6dc718b9bd3da36a1442ebb18fe9da30",
      "serviceUrl":"mydomain.com.location",
      "orgid":"ling.com",
      "version":"1.2",
      "arch":"amd64",
      "containers": [
        {
          "name": "/dc23c045eb64e1637d027c4b0236512e89b2fddd3f06290c7b2354421d9d8e0d-location",
          "image": "summit.hovitos.engineering/x86/location:v1.2",
          "created": 1506086099,
          "state": "running"
        }
      ]
    }
  ]
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutNodeStatusRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePutStatusRoute: Route = (put & path("orgs" / Segment / "nodes" / Segment / "status") & entity(as[PutNodeStatusRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          db.run(reqBody.toNodeStatusRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/status result: " + v)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodestatus", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource status table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("status.added.or.updated")))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/status ===============================
  @DELETE
  @Path("{id}/status")
  @Operation(summary = "Deletes the status of a node", description = "Deletes the status of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeDeleteStatusRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "status")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeStatusTQ.getNodeStatus(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/status result: " + v)
            if (v > 0) {
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodestatus", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
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
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.status.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/policy ================================ */
  @GET
  @Path("{id}/policy")
  @Operation(summary = "Returns the node policy", description = "Returns the node run time policy. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[NodePolicy])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetPolicyRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "policy")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
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
  @Path("{id}/policy")
  @Operation(summary = "Adds/updates the node policy", description = "Adds or updates the policy of a node. This is called by the node or owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated.")),
    requestBody = new RequestBody(description = """
```
{
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing"
      "type": "string"   // optional, the type of the 'value': string, int, float, boolean, list of string, version
    }
  ],
  "constraints": [
    "a == b"
  ]
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutNodePolicyRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePutPolicyRoute: Route = (put & path("orgs" / Segment / "nodes" / Segment / "policy") & entity(as[PutNodePolicyRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          db.run(reqBody.toNodePolicyRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/policy result: " + v)
              NodesTQ.setLastHeartbeat(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastHeartbeat result: " + n)
              try {
                val numUpdated = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
                if (numUpdated > 0) {
                  val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodepolicies", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
                  nodeChange.insert.asTry
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
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/policy ===============================
  @DELETE
  @Path("{id}/policy")
  @Operation(summary = "Deletes the policy of a node", description = "Deletes the policy of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeDeletePolicyRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "policy")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodePolicyTQ.getNodePolicy(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/policy result: " + v)
            if (v > 0) {
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodepolicies", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => // there were no db policy, but determine if it actually found it or not
            logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + " updating resource policy table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements ================================ */
  @GET
  @Path("{id}/agreements")
  @Operation(summary = "Returns all agreements this node is in", description = "Returns all agreements that this node is part of. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetNodeAgreementsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetAgreementsRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "agreements")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreements(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/agreements result size: ${list.size}")
          val agreements = list.map(e => e.agId -> e.toNodeAgreement).toMap
          val code = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements/{agid} ================================ */
  @GET
  @Path("{id}/agreements/{agid}")
  @Operation(summary = "Returns an agreement for a node", description = "Returns the agreement with the specified agid for the specified node id. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetNodeAgreementsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetAgreementRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment)) { (orgid, id, agrId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreement(compositeId, agrId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/nodes/$id/agreements/$agrId result size: ${list.size}")
          val agreements = list.map(e => e.agId -> e.toNodeAgreement).toMap
          val code = if (agreements.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeAgreementsResponse(agreements, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  @PUT
  @Path("{id}/agreements/{agid}")
  @Operation(summary = "Adds/updates an agreement of a node", description = "Adds a new agreement of a node, or updates an existing agreement. This is called by the node or owning user to give their information about the agreement.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to be updated."),
      new Parameter(name = "agid", in = ParameterIn.PATH, description = "ID of the agreement to be added/updated.")),
    requestBody = new RequestBody(description = """
```
{
  "services": [          // specify this for CS-type agreements
    {"orgid": "myorg", "url": "mydomain.com.rtlsdr"}
  ],
  "agreementService": {          // specify this for pattern-type agreements
    "orgid": "myorg",     // currently set to the node id, but not used
    "pattern": "myorg/mypattern",    // composite pattern (org/pat)
    "url": "myorg/mydomain.com.sdr"   // composite service url (org/svc)
  },
  "state": "negotiating"    // current agreement state: negotiating, signed, finalized, etc.
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutNodeAgreementRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePutAgreementRoute: Route = (put & path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment) & entity(as[PutNodeAgreementRequest])) { (orgid, id, agrId, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.WRITE) { _ =>
      validate(reqBody.getAnyProblem.isEmpty, "Problem in request body") {
        complete({
          db.run(NodeAgreementsTQ.getNumOwned(compositeId).result.flatMap({ xs =>
            logger.debug("PUT /orgs/"+orgid+"/nodes/"+id+"/agreements/"+agrId+" num owned: "+xs)
            val numOwned = xs
            val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
            if (maxAgreements == 0 || numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
              reqBody.toNodeAgreementRow(compositeId, agrId).upsert.asTry
            }
            else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.agreements.for.node", maxAgreements) )).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/nodes/" + id + "/agreements/" + agrId + " result: " + v)
              NodesTQ.setLastHeartbeat(compositeId, ApiTime.nowUTC).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /orgs/" + orgid + "/nodes/" + id + "/policy result: " + v)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodeagreements", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug("Update /orgs/" + orgid + "/nodes/" + id + " lastHeartbeat result: " + n)
              try {
                val numUpdated = n.toString.toInt // i think n is an AnyRef so we have to do this to get it to an int
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
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.inserted.or.updated", agrId, compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements ===============================
  @DELETE
  @Path("{id}/agreements")
  @Operation(summary = "Deletes all agreements of a node", description = "Deletes all of the current agreements of a node. Can be run by the owning user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeDeleteAgreementsRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "agreements")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(NodeAgreementsTQ.getAgreements(compositeId).delete.asTry.flatMap({
          case Success(v) =>
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /nodes/" + id + "/agreements result: " + v)
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodeagreements", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.node.agreements.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreements.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreements.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  @DELETE
  @Path("{id}/agreements/{agid}")
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
  def nodeDeleteAgreementRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "agreements" / Segment)) { (orgid, id, agrId) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(NodeAgreementsTQ.getAgreement(compositeId,agrId).delete.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /nodes/" + id + "/agreements/" + agrId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodeagreements", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              nodeChange.insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.agreement.not.found", agrId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /nodes/" + id + "/agreements/" + agrId + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.agreement.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.agreement.not.deleted", agrId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/nodes/{id}/msgs ===============================
  @POST
  @Path("{id}/msgs")
  @Operation(summary = "Sends a msg from an agbot to a node", description = "Sends a msg from an agbot to a node. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the node's public key). Can be run by any agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node to send a message to.")),
    requestBody = new RequestBody(description = """
```
{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",    // msg to be sent to the node
  "ttl": 86400       // time-to-live of this msg, in seconds
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostNodesMsgsRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodePostMsgRoute: Route = (post & path("orgs" / Segment / "nodes" / Segment / "msgs") & entity(as[PostNodesMsgsRequest])) { (orgid, id, reqBody) =>
    val compositeId = OrgAndId(orgid, id).toString
    exchAuth(TNode(compositeId),Access.SEND_MSG_TO_NODE) { ident =>
      complete({
        val agbotId = ident.creds.id      //someday: handle the case where the acls allow users to send msgs
        var msgNum = ""
        // Remove msgs whose TTL is past, then check the mailbox is not full, then get the agbot publicKey, then write the nodemsgs row, all in the same db.run thread
        db.run(NodeMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
          logger.debug("POST /orgs/"+orgid+"/nodes/"+id+"/msgs delete expired result: "+xs.toString)
          NodeMsgsTQ.getNumOwned(compositeId).result
        }).flatMap({ xs =>
          logger.debug("POST /orgs/"+orgid+"/nodes/"+id+"/msgs mailbox size: "+xs)
          val mailboxSize = xs
          val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
          if (maxMessagesInMailbox == 0 || mailboxSize < maxMessagesInMailbox) AgbotsTQ.getPublicKey(agbotId).result.asTry
          else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("node.mailbox.full", compositeId, maxMessagesInMailbox) )).asTry
        }).flatMap({
          case Success(v) =>
            logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/msgs agbot publickey result: " + v)
            if (v.nonEmpty) { // it seems this returns success even when the agbot is not found
              val agbotPubKey = v.head
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
            val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodemsgs", ResourceChangeConfig.CREATED, ApiTime.nowUTC)
            nodeChange.insert.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("POST /orgs/" + orgid + "/nodes/" + id + "/msgs update changes table : " + v)
            (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.inserted", msgNum)))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t) =>
            if (t.getMessage.contains("is not present in table")) (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.msg.nodeid.not.found", compositeId, t.getMessage)))
            else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.inserted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/nodes/{id}/msgs ================================ */
  @GET
  @Path("{id}/msgs")
  @Operation(summary = "Returns all msgs sent to this node", description = "Returns all msgs that have been sent to this node. They will be returned in the order they were sent. All msgs that have been sent to this node will be returned, unless the node has deleted some, or some are past their TTL. Can be run by a user or the node.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the node.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetNodeMsgsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetMsgsRoute: Route = (get & path("orgs" / Segment / "nodes" / Segment / "msgs")) { (orgid, id) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId),Access.READ) { _ =>
      complete({
        // Remove msgs whose TTL is past, and then get the msgs for this node
        db.run(NodeMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs delete expired result: "+xs.toString)
          NodeMsgsTQ.getMsgs(compositeId).result
        })).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs result size: "+list.size)
          //logger.debug("GET /orgs/"+orgid+"/nodes/"+id+"/msgs result: "+list.toString)
          val listSorted = list.sortWith(_.msgId < _.msgId)
          val msgs = listSorted.map(_.toNodeMsg).toList
          val code = if (msgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetNodeMsgsResponse(msgs, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/nodes/{id}/msgs/{msgid} ===============================
  @DELETE
  @Path("{id}/msgs/{msgid}")
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
  def nodeDeleteMsgRoute: Route = (delete & path("orgs" / Segment / "nodes" / Segment / "msgs" / Segment)) { (orgid, id, msgIdStr) =>
    val compositeId = OrgAndId(orgid,id).toString
    exchAuth(TNode(compositeId), Access.WRITE) { _ =>
      complete({
        try {
          val msgId =  msgIdStr.toInt   // this can throw an exception, that's why this whole section is in a try/catch
          db.run(NodeMsgsTQ.getMsg(compositeId,msgId).delete.asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("DELETE /nodes/" + id + "/msgs/" + msgId + " result: " + v)
              if (v > 0) { // there were no db errors, but determine if it actually found it or not
                val nodeChange = ResourceChangeRow(0, orgid, id, "node", "false", "nodemsgs", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
                nodeChange.insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("node.msg.not.found", msgId, compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("DELETE /nodes/" + id + "/msgs/" + msgId + " updated in changes table: " + v)
              (HttpCode.DELETED,  ApiResponse(ApiRespType.OK, ExchMsg.translate("node.msg.deleted")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("node.msg.not.deleted", msgId, compositeId, t.toString)))
          })
        } catch { case e: Exception => (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
      }) // end of complete
    } // end of exchAuth
  }

}
