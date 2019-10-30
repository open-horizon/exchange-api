/** Services routes for all of the /orgs/{orgid}/nodes api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/nodes routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/nodes */
case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int)
case class GetNodeAttributeResponse(attribute: String, value: String)

// Tried this to have names on the tuple returned from the db, but didn't work...
case class PatternSearchHashElement(msgEndPoint: String, publicKey: String, noAgreementYet: Boolean)

case class PatternNodeResponse(id: String, msgEndPoint: String, publicKey: String)
case class PostPatternSearchResponse(nodes: List[PatternNodeResponse], lastIndex: Int)


case class PostNodeHealthRequest(lastTime: String, nodeOrgids: Option[List[String]]) {
  def validate() = {}
}

case class NodeHealthAgreementElement(lastUpdated: String)
class NodeHealthHashElement(var lastHeartbeat: String, var agreements: Map[String,NodeHealthAgreementElement])
case class PostNodeHealthResponse(nodes: Map[String,NodeHealthHashElement])

// Leaving this here for the UI wanting to implement filtering later
case class PostNodeErrorRequest() {
  def validate() = {}
}
case class PostNodeErrorResponse(nodes: scala.Seq[String])

case class PostServiceSearchRequest(orgid: String, serviceURL: String, serviceVersion: String, serviceArch: String) {
  def validate() = {}
}
case class PostServiceSearchResponse(nodes: scala.collection.Seq[(String, String)])


/** Input for service-based (citizen scientist) search, POST /orgs/"+orgid+"/search/nodes */
case class PostSearchNodesRequest(desiredServices: List[RegServiceSearch], secondsStale: Int, propertiesToReturn: Option[List[String]], startIndex: Int, numEntries: Int) {
  /** Halts the request with an error msg if the user input is invalid. */
  def validate() = {
    for (svc <- desiredServices) {
      // now we support more than 1 agreement for a service
      svc.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  /** Returns the services that match all of the search criteria */
  def matches(nodes: Map[String,Node], agHash: AgreementsHash)(implicit logger: Logger): PostSearchNodesResponse = {
    // logger.trace(agHash.agHash.toString)

    // Loop thru the existing nodes and services in the DB. (Should probably make this more FP style)
    var nodesResp: List[NodeResponse] = List()
    for ((id,d) <- nodes) {       // the db query now filters out stale nodes
      // Get all services for this node that are not max'd out on agreements
      var availableServices: List[RegService] = List()
      for (m <- d.registeredServices) {
        breakable {
          // do not even bother checking this against the search criteria if this service is already at its agreement limit
          val agNode = agHash.agHash.get(id)
          agNode match {
            case Some(agNode2) => val agNum = agNode2.get(m.url)  // m.url is the composite org/svcurl
              agNum match {
                case Some(agNum2) => if (agNum2 >= m.numAgreements) break // this is really a continue
                case None => ; // no agreements for this service, nothing to do
              }
            case None => ; // no agreements for this node, nothing to do
          }
          availableServices = availableServices :+ m
        }
      }

      // We now have several services for 1 node from the db (that are not max'd out on agreements). See if all of the desired services are satisfied.
      var servicesResp: List[RegService] = List()
      breakable {
        for (desiredService <- desiredServices) {
          var found: Boolean = false
          breakable {
            for (availableService <- availableServices) {
              if (desiredService.matches(availableService)) {
                servicesResp = servicesResp :+ availableService
                found = true
                break
              }
            }
          }
          if (!found) break // we did not find one of the required services, so end early
        }
      }

      if (servicesResp.length == desiredServices.length) {
        // all required services were available in this node, so add this node to the response list
        nodesResp = nodesResp :+ NodeResponse(id, d.name, servicesResp, d.userInput, d.msgEndPoint, d.publicKey, d.arch)
      }
    }
    // return the search result to the rest client
    PostSearchNodesResponse(nodesResp, 0)
  }
}

case class NodeResponse(id: String, name: String, services: List[RegService], userInput: List[OneUserInputService], msgEndPoint: String, publicKey: String, arch: String)
case class PostSearchNodesResponse(nodes: List[NodeResponse], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/<node-id> */
case class PutNodesRequest(token: Option[String], name: String, pattern: String, registeredServices: Option[List[RegService]], userInput: Option[List[OneUserInputService]], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: String, arch: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  /** Halts the request with an error msg if the user input is invalid. */
  def validate() = {
    // if (publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    if (pattern != "" && """.*/.*""".r.findFirstIn(pattern).isEmpty) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.must.have.orgid.prepended")))
    for (m <- registeredServices.getOrElse(List())) {
      // now we support more than 1 agreement for a MS
      // if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { NodesTQ.validateServiceIds(userInput.getOrElse(List())) }

  /** Get the db actions to insert or update all parts of the node */
  def getDbUpsert(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2 = registeredServices.getOrElse(List()).map(rs => RegService(rs.url,rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties))
    if(token.getOrElse("") == ""){NodeRowNoToken(id, orgid, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).upsert}
    NodeRow(id, orgid, hashedTok, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).upsert
  }

  /** Get the db actions to update all parts of the node. This is run, instead of getDbUpsert(), when it is a node doing it,
   * because we can't let a node create new nodes. */
  def getDbUpdate(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = {
    // default new field configState in registeredServices
    val rsvc2 = registeredServices.getOrElse(List()).map(rs => RegService(rs.url,rs.numAgreements, rs.configState.orElse(Some("active")), rs.policy, rs.properties))
    if(token.getOrElse("") == "") {NodeRowNoToken(id, orgid, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).update}
    NodeRow(id, orgid, hashedTok, name, owner, pattern, write(rsvc2), write(userInput), msgEndPoint.getOrElse(""), write(softwareVersions), ApiTime.nowUTC, publicKey, arch.getOrElse("")).update
  }
}

case class PatchNodesRequest(token: Option[String], name: Option[String], pattern: Option[String], registeredServices: Option[List[RegService]], userInput: Option[List[OneUserInputService]], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: Option[String], arch: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the node, and the attribute name being updated. */
  def getDbUpdate(id: String, hashedPw: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //todo: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this node
    token match {
      case Some(token2) => if (token2 == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("token.cannot.be.empty.string")))
        //val tok = if (Password.isHashed(token2)) token2 else Password.hash(token2)
        return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, hashedPw, lastHeartbeat)), "token")
      case _ => ;
    }
    softwareVersions match {
      case Some(swv) => val swVersions = if (swv.nonEmpty) write(softwareVersions) else ""
        return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat)).update((id, swVersions, lastHeartbeat)), "softwareVersions")
      case _ => ;
    }
    registeredServices match {
      case Some(rsvc) => val regSvc = if (rsvc.nonEmpty) write(registeredServices) else ""
        return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat)).update((id, regSvc, lastHeartbeat)), "registeredServices")
      case _ => ;
    }
    name match { case Some(name2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name2, lastHeartbeat)), "name"); case _ => ; }
    pattern match { case Some(pattern2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.pattern,d.lastHeartbeat)).update((id, pattern2, lastHeartbeat)), "pattern"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.userInput,d.lastHeartbeat)).update((id, write(input), lastHeartbeat)), "userInput"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint2, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey2, lastHeartbeat)), "publicKey"); case _ => ; }
    arch match { case Some(arch2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.arch,d.lastHeartbeat)).update((id, arch2, lastHeartbeat)), "arch"); case _ => ; }
    return (null, null)
  }
}

case class PostNodeConfigStateRequest(org: String, url: String, configState: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  //def logger: Logger    // get access to the logger object in ExchangeApiApp

  def validate() = {
    if (configState != "suspended" && configState != "active") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("configstate.must.be.suspended.or.active")))
  }

  // Match registered service urls (which are org/url) to the input org and url
  def isMatch(compositeUrl: String): Boolean = {
    val reg = """^(\S+?)/(\S+)$""".r
    val (comporg, compurl) = compositeUrl match {
      case reg(o,u) => (o, u)
      case _ => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("configstate.must.be.suspended.or.active", compositeUrl)))
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
    if (regServices == "") halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.has.no.services")))
    val regSvcs = read[List[RegService]](regServices)
    if (regSvcs.isEmpty) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.has.no.services")))

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
    //if (newRegSvcs.sameElements(regSvcs)) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, "did not find any registeredServices that matched the given org and url criteria."))
    if (!matchingSvcFound) halt(HttpCode.NOT_FOUND, ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("did.not.find.registered.services")))
    if (newRegSvcs == regSvcs) {
      println(ExchangeMessage.translateMessage("no.db.update.necessary"))
      //logger.debug("No db update necessary, all relevant config states already correct")
      return DBIO.successful(1)    // all the configStates were already set correctly, so nothing to do
    }

    // Convert from struct back to string and return db action to update that
    val newRegSvcsString = write(newRegSvcs)
    return (for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.regServices,d.lastHeartbeat)).update((id, newRegSvcsString, ApiTime.nowUTC))
  }
}

case class PutNodeStatusRequest(connectivity: Map[String,Boolean], services: List[OneService]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = { }
  var runningServices = "|"
  for(s <- services){
    runningServices = runningServices + s.orgid + "/" + s.serviceUrl + "_" + s.version + "_" + s.arch + "|"
  }
  def toNodeStatusRow(nodeId: String) = NodeStatusRow(nodeId, write(connectivity), write(services), runningServices, ApiTime.nowUTC)
}

case class PutNodeErrorRequest(errors: List[Any]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = { }

  def toNodeErrorRow(nodeId: String) = NodeErrorRow(nodeId, write(errors), ApiTime.nowUTC)
}

case class PutNodePolicyRequest(properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of string", "version")
      for (p <- properties.getOrElse(List())) {
        if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
          halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("property.type.must.be", p.`type`.get, validTypes.mkString(", "))))
        }
      }
  }

  def toNodePolicyRow(nodeId: String) = NodePolicyRow(nodeId, write(properties), write(constraints), ApiTime.nowUTC)
}


/** Output format for GET /orgs/{orgid}/nodes/{id}/agreements */
case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/{id}/agreements/<agreement-id> */
case class PutNodeAgreementRequest(services: Option[List[NAService]], agreementService: Option[NAgrService], state: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    if (services.isEmpty && agreementService.isEmpty) {
      halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("must.specify.service.or.agreementservice")))
    }
  }

  def toNodeAgreementRow(nodeId: String, agId: String) = {
    if (agreementService.isDefined) NodeAgreementRow(agId, nodeId, write(services), agreementService.get.orgid, agreementService.get.pattern, agreementService.get.url, state, ApiTime.nowUTC)
    else NodeAgreementRow(agId, nodeId, write(services), "", "", "", state, ApiTime.nowUTC)
  }
}


/** Input body for POST /orgs/{orgid}/nodes/{id}/msgs */
case class PostNodesMsgsRequest(message: String, ttl: Int)

/** Response for GET /orgs/{orgid}/nodes/{id}/msgs */
case class GetNodeMsgsResponse(messages: List[NodeMsg], lastIndex: Int)


/** Implementation for all of the /orgs/{orgid}/nodes routes */
trait NodesRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  implicit def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats
  // implicit def formats: org.json4s.Formats{val dateFormat: org.json4s.DateFormat; val typeHints: org.json4s.TypeHints}

  /* ====== GET /orgs/{orgid}/nodes ================================
    This is of type org.scalatra.swagger.SwaggerOperation
    apiOperation() is a method of org.scalatra.swagger.SwaggerSupport. It returns org.scalatra.swagger.SwaggerSupportSyntax$$OperationBuilder
    and then all of the other methods below that (summary, notes, etc.) are all part of OperationBuilder and return OperationBuilder.
    So instead of the infix invocation in the code below, we could alternatively code it like this:
    val getNodes = apiOperation[GetNodesResponse]("getNodes").summary("Returns matching nodes").description("Based on the input selection criteria, returns the matching nodes (RPis) in the exchange DB.")
  */
  val getNodes =
    (apiOperation[GetNodesResponse]("getNodes")
      summary("Returns all nodes")
      description("""Returns all nodes (RPis) in the exchange DB. Can be run by a user or agbot (but not a node).""")
      // authorizations("basicAuth")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include nodes with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include nodes with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include nodes with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
    )
      // this does not work, because scalatra will not give me the request.body on a GET
      // parameters(Parameter("body", DataType[GetNodeRequest], Option[String]("Node search criteria"), paramType = ParamType.Body))
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  /** operation() is a method of org.scalatra.swagger.SwaggerSupport that takes SwaggerOperation and returns RouteTransformer */
  get("/orgs/:orgid/nodes", operation(getNodes)) ({
    // try {    // this try/catch does not get us much more than what scalatra does by default
    // I think the request member is of type org.eclipse.jetty.server.Request, which implements interfaces javax.servlet.http.HttpServletRequest and javax.servlet.ServletRequest
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    // throw new IllegalArgumentException("arg 1 was wrong...")
    // The nodes, microservices, and properties tables all combine to form the Node object, so we do joins to get them all.
    // Note: joinLeft is necessary here so that if no micros exist for a node, we still get the node (and likewise for the micro if no props exist).
    //    This means m and p below are wrapped in Option because they may not always be there
    //var q = for {
    //  ((d, m), p) <- NodesTQ.getAllNodes(orgid) joinLeft RegMicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
    //} yield (d, m, p)
    var q = NodesTQ.getAllNodes(orgid)

    // add filters
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.id like id) else q = q.filter(_.id === id) })
    params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_.name like name) else q = q.filter(_.name === name) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes result size: "+list.size)
      val nodes = NodesTQ.parseJoin(superUser, list)
      if (nodes.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodesResponse(nodes, 0)
    })
    // } catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Oops! Somthing unexpected happened: "+e)) }
  })

  /* ====== GET /orgs/{orgid}/nodes/{id} ================================ */
  val getOneNode =
    (apiOperation[GetNodesResponse]("getOneNode")
      summary("Returns a node")
      description("""Returns the node (RPi) with the specified id in the exchange DB. Can be run by that node, a user, or an agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the services). If not specified, the entire node resource (including services) will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad.input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id", operation(getOneNode)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val ident = authenticate().authorizeTo(TNode(id),Access.READ)
    val isSuperUser = ident.isSuperUser
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the node
        val q = NodesTQ.getAttribute(id, attribute)
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("not.a.node.attribute", attribute)))
        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+" attribute result: "+list.size)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetNodeAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("not.found"))     // validateAccessToNode() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })

      case None => ;  // Return the whole node
        val q = NodesTQ.getNode(id)
        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+" result: "+list.size)
          if (list.nonEmpty) {
            val nodes = NodesTQ.parseJoin(isSuperUser, list)
            resp.setStatus(HttpCode.OK)
            GetNodesResponse(nodes, 0)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("not.found"))     // validateAccessToNode() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })
    }
  })


  /** From the given db joined node/agreement rows, build the output node health hash and return it.
     This is shared between POST /org/{orgid}/patterns/{pat-id}/nodehealth and POST /org/{orgid}/search/nodehealth
    */
  def buildNodeHealthHash(list: scala.Seq[(String, String, Option[String], Option[String])]): Map[String,NodeHealthHashElement] = {
    // Go thru the rows and build a hash of the nodes, adding the agreement to its value as we encounter them
    val nodeHash = new MutableHashMap[String,NodeHealthHashElement]     // key is node id, value has lastHeartbeat and the agreements map
    for ( (nodeId, lastHeartbeat, agrId, agrLastUpdated) <- list ) {
      nodeHash.get(nodeId) match {
        case Some(nodeElement) => agrId match {    // this node is already in the hash, add the agreement if it's there
          case Some(agId) => nodeElement.agreements = nodeElement.agreements + ((agId, NodeHealthAgreementElement(agrLastUpdated.getOrElse(""))))    // if we are here, lastHeartbeat is already set and the agreement Map is already created
          case None => ;      // no agreement to add to the agreement hash
        }
        case None => agrId match {      // this node id not in the hash yet, add it
          case Some(agId) => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map(agId -> NodeHealthAgreementElement(agrLastUpdated.getOrElse("")))))
          case None => nodeHash.put(nodeId, new NodeHealthHashElement(lastHeartbeat, Map()))
        }
      }
    }
    return nodeHash.toMap
  }

  // ======== POST /org/{orgid}/patterns/{pat-id}/nodehealth ========================
  val postPatternNodeHealth =
    (apiOperation[PostNodeHealthResponse]("postPatternNodeHealth")
      summary("Returns agreement health of nodes of a particular pattern")
      description """Returns the lastHeartbeat and agreement times for all nodes that are this pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]",   // only return nodes that have changed since this time, empty string returns all
  "nodeOrgids": [ "org1", "org2", "..." ]   // if not specified, defaults to the same org the pattern is in
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostNodeHealthRequest],
          Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postPatternNodeHealth2 = (apiOperation[PostNodeHealthRequest]("postPatternNodeHealth2") summary("a") description("a"))

  /** Called by the agbot to get recent info about nodes with this pattern (and the agreements the node has). */
  post("/orgs/:orgid/patterns/:pattern/nodehealth", operation(postPatternNodeHealth)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositePat = OrgAndId(orgid,pattern).toString
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostNodeHealthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    searchProps.validate()
    val nodeOrgids = searchProps.nodeOrgids.getOrElse(List(orgid)).toSet
    logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth criteria: "+searchProps.toString)
    val resp = response
    /*
      Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
      The filter is: n.pattern==ourpattern && n.lastHeartbeat>=lastTime
      Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
    */
    val lastTime = if (searchProps.lastTime != "") searchProps.lastTime else ApiTime.beginningUTC
    val q = for {
      (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
    } yield (n.id, n.lastHeartbeat, a.map(_.agId), a.map(_.lastUpdated))

    db.run(q.result).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result size: "+list.size)
      //logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result: "+list.toString)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.POST_OK)
        PostNodeHealthResponse(buildNodeHealthHash(list))
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        PostNodeHealthResponse(Map[String,NodeHealthHashElement]())
      }
    })
  })



  //todo: remove this once anax 2.23.7 hits prod
  // ======== POST /orgs/{orgid}/search/nodes ========================
  val postSearchNodes =
    (apiOperation[PostSearchNodesResponse]("postSearchNodes")
      summary("Returns matching nodes")
      description """Based on the input selection criteria, returns the matching nodes (RPis) in the exchange DB. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "desiredServices": [    // list of data services you are interested in
    {
      "url": "myorg/mydomain.com.rtlsdr",    // composite svc identifier (org/url)
      "properties": [    // list of properties to match specific nodes/services
        {
          "name": "arch",         // typical property names are: arch, version, dataVerification, memory
          "value": "arm",         // should always be a string (even for boolean and int). Use "*" for wildcard
          "propType": "string",   // valid types: string, list, version, boolean, int, or wildcard
          "op": "="               // =, <=, >=, or in
        }
      ]
    }
  ],
  "secondsStale": 60,     // max number of seconds since the exchange has heard from the node, 0 if you do not care
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostSearchNodesRequest],
          Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postSearchNodes2 = (apiOperation[PostSearchNodesRequest]("postSearchNodes2") summary("a") description("a"))

  /** Normally called by the agbot to search for available nodes. */
  post("/orgs/:orgid/search/nodes", operation(postSearchNodes)) ({
    val orgid = params("orgid")
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostSearchNodesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/search/nodes criteria: "+searchProps.desiredServices.toString)
    val resp = response
    // Narrow down the db query results as much as possible with db selects, then searchProps.matches will do the rest.
    var q = NodesTQ.getNonPatternNodes(orgid).filter(_.publicKey =!= "")
    // Also filter out nodes that are too stale (have not heartbeated recently)
    if (searchProps.secondsStale > 0) q = q.filter(_.lastHeartbeat >= ApiTime.pastUTC(searchProps.secondsStale))

    var agHash: AgreementsHash = null
    db.run(NodeAgreementsTQ.getAgreementsWithState(orgid).result.flatMap({ agList =>
      logger.debug("POST /orgs/" + orgid + "/search/nodes aglist result size: " + agList.size)
      //logger.trace("POST /orgs/" + orgid + "/search/nodes aglist result: " + agList.toString)
      agHash = new AgreementsHash(agList)
      q.result // queue up our node query next
    })).map({ list =>
      logger.debug("POST /orgs/" + orgid + "/search/nodes result size: " + list.size)
      // logger.trace("POST /orgs/"+orgid+"/search/nodes result: "+list.toString)
      // logger.trace("POST /orgs/"+orgid+"/search/nodes agHash: "+agHash.agHash.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.POST_OK) //todo: this check only catches if there are no nodes at all, not the case in which there are some nodes, but they do not have the right services
      else resp.setStatus(HttpCode.NOT_FOUND)
      val nodes = new MutableHashMap[String,Node]    // the key is node id
      if (list.nonEmpty) for (a <- list) nodes.put(a.id, a.toNode(false))
      searchProps.matches(nodes.toMap, agHash)
    })
  })

  // ======== POST /org/{orgid}/search/nodes/error ========================
  val postSearchNodeError =
    (apiOperation[PostNodeErrorResponse]("postSearchNodeError")
      summary("Returns nodes in an error state")
      description """Returns a list of the id's of nodes in an error state. Can be run by a user or agbot (but not a node). No request body is currently required."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postSearchNodeError2 = (apiOperation[PostNodeHealthRequest]("postSearchNodeError2") summary("a") description("a"))

  /** Called by the UI to get the count of nodes in an error state. */
  post("/orgs/:orgid/search/nodes/error", operation(postSearchNodeError)) ({
    val orgid = params("orgid")
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    val q = for {
      (n) <- NodeErrorTQ.rows.filter(_.errors =!= "").filter(_.errors =!= "[]")
    } yield n.nodeId

    db.run(q.result).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/search/nodes/error result size: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.POST_OK)
        PostNodeErrorResponse(list)
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
      }
    })
  })

  // =========== POST /orgs/{orgid}/search/nodes/service  ===============================

  val postServiceSearch =
    (apiOperation[PostServiceSearchResponse]("postServiceSearch")
      summary("Returns the nodes a service is running on")
      description
      """Returns a list of all the nodes a service is running on. The **request body** structure:
```
{
  "orgid": "string",   // orgid of the service to be searched on
  "serviceURL": "string",
  "serviceVersion": "string",
  "serviceArch": "string"
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postServiceSearch2 = (apiOperation[PostNodeHealthRequest]("postSearchNodeHealth2") summary("a") description("a"))

  /** Called by the agbot to get recent info about nodes with no pattern (and the agreements the node has). */
  post("/orgs/:orgid/search/nodes/service", operation(postServiceSearch)) ({
    val orgid = params("orgid")
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostServiceSearchRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    searchProps.validate()
    // service = svcUrl_svcVersion_svcArch
    val service = searchProps.serviceURL+"_"+searchProps.serviceVersion+"_"+searchProps.serviceArch
    logger.debug("POST /orgs/"+orgid+"/search/nodehealth criteria: "+searchProps.toString)
    val resp = response
    val orgService = "%|"+searchProps.orgid+"/"+service+"|%"
    val q = for {
      (n, s) <- (NodesTQ.rows.filter(_.orgid === orgid)) join (NodeStatusTQ.rows.filter(_.runningServices like orgService)) on (_.id === _.nodeId)
    } yield (n.id, n.lastHeartbeat)

    db.run(q.result).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/services/"+service+"/search result size: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.POST_OK)
        PostServiceSearchResponse(list)
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
      }
    })
  })

  // ======== POST /org/{orgid}/search/nodehealth ========================
  val postSearchNodeHealth =
    (apiOperation[PostNodeHealthResponse]("postSearchNodeHealth")
      summary("Returns agreement health of nodes with no pattern")
      description """Returns the lastHeartbeat and agreement times for all nodes in this org that do not have a pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]"   // only return nodes that have changed since this time, empty string returns all
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostNodeHealthRequest],
          Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postSearchNodeHealth2 = (apiOperation[PostNodeHealthRequest]("postSearchNodeHealth2") summary("a") description("a"))

  /** Called by the agbot to get recent info about nodes with no pattern (and the agreements the node has). */
  post("/orgs/:orgid/search/nodehealth", operation(postSearchNodeHealth)) ({
    val orgid = params("orgid")
    //val pattern = params("patid")
    //val compositePat = OrgAndId(orgid,pattern).toString
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostNodeHealthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/search/nodehealth criteria: "+searchProps.toString)
    val resp = response
    /*
      Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
      The filter is: n.pattern=="" && n.lastHeartbeat>=lastTime
      Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
    */
    val lastTime = if (searchProps.lastTime != "") searchProps.lastTime else ApiTime.beginningUTC
    val q = for {
      (n, a) <- NodesTQ.rows.filter(_.orgid === orgid).filter(_.pattern === "").filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
    } yield (n.id, n.lastHeartbeat, a.map(_.agId), a.map(_.lastUpdated))

    db.run(q.result).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/search/nodehealth result size: "+list.size)
      //logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result: "+list.toString)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.POST_OK)
        PostNodeHealthResponse(buildNodeHealthHash(list))
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        PostNodeHealthResponse(Map[String,NodeHealthHashElement]())
      }
    })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id} ===============================
  val putNodes =
    (apiOperation[Map[String,String]]("putNodes")
      summary "Adds/updates a node"
      description """Adds a new edge node to the exchange DB, or updates an existing node. This must be called by the user to add a node, and then can be called by that user or node to update itself. The **request body** structure:

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
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutNodesRequest],
          Option[String]("Node object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putNodes2 = (apiOperation[PutNodesRequest]("putNodes2") summary("a") description("a"))  // for some bizarre reason, the PutNodesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id", operation(putNodes)) ({
    // consider writing a customer deserializer that will do error checking on the body, see: https://gist.github.com/fehguy/4191861#file-gistfile1-scala-L74
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val ident = authenticate().authorizeTo(TNode(id),Access.WRITE)
    val node = try { parse(request.body).extract[PutNodesRequest] }
    catch {
      case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e)))
    }
    node.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    val patValidateAction = if (node.pattern != "") PatternsTQ.getPattern(node.pattern).length.result else DBIO.successful(1)
    val (valServiceIdActions, svcRefs) = node.validateServiceIds  // to check that the services referenced in userInput exist
    val hashedTok = Password.hash(node.token.getOrElse(""))
    db.run(patValidateAction.asTry.flatMap({ xs =>
      // Check if pattern exists, then get services referenced
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" pattern validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0) valServiceIdActions.asTry
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("pattern.not.in.exchange"))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      // Check if referenced services exist, then get whether node is using policy
      logger.debug("PUT /orgs/"+orgid+"/nodes"+bareId+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(id).result.asTry
          else {
            val errStr = if (invalidIndex < svcRefs.length) ExchangeMessage.translateMessage("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
            else ExchangeMessage.translateMessage("service.not.in.exchange.index", Nth(invalidIndex+1))
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      // Check if node is using policy, then get num nodes already owned
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" policy related attrs: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {
            val (existingPattern, existingPublicKey) = v.head
            if (node.pattern!="" && existingPattern=="" && existingPublicKey!="") DBIO.failed(new Throwable(ExchangeMessage.translateMessage("not.pattern.when.policy"))).asTry
            else NodesTQ.getNumOwned(owner).result.asTry   // they are not trying to switch from policy to pattern, so we can continue
          }
          else NodesTQ.getNumOwned(owner).result.asTry    // node doesn't exit yet, we can continue
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      // Check if num nodes owned is below limit, then create/update node
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" num owned: "+xs)
      xs match {
        case Success(numOwned) => val maxNodes = ExchConfig.getInt("api.limits.maxNodes")
          if (maxNodes == 0 || numOwned <= maxNodes || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
            val action = if (owner == "") node.getDbUpdate(id, orgid, owner, hashedTok) else node.getDbUpsert(id, orgid, owner, hashedTok)
            action.transactionally.asTry
          }
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("over.max.limit.of.nodes", maxNodes))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      // Check creation/update of node, and other errors
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(_) => AuthCache.putNodeAndOwner(id, hashedTok, node.token.getOrElse(""), owner)
          //AuthCache.ids.putNode(id, hashedTok, node.token)
          //AuthCache.nodesOwner.putOne(id, owner)
          resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.not.inserted.or.updated", id, t.getMessage))
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("node.not.inserted.or.updated", id, t.getMessage))
          }
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/nodes/{id} ===============================
  val patchNodes =
    (apiOperation[Map[String,String]]("patchNodes")
      summary "Updates 1 attribute of a node"
      description """Updates some attributes of a node (RPi) in the exchange DB. This can be called by the user or the node."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchNodesRequest],
          Option[String]("Node object that contains attributes to updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchNodes2 = (apiOperation[PatchNodesRequest]("patchNodes2") summary("a") description("a"))  // for some bizarre reason, the PatchNodesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/nodes/:id", operation(patchNodes)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    if((!request.body.contains("token") && !request.body.contains("name") && !request.body.contains("pattern") && !request.body.contains("registeredServices") && !request.body.contains("userInput") && !request.body.contains("msgEndPoint") && !request.body.contains("softwareVersions") && !request.body.contains("publicKey") && !request.body.contains("arch")) || (request.body.contains("name") && request.body.contains("value") && !request.body.contains("userInput"))){
      halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("invalid.input.message", request.body)))
    }
    val node = try { parse(request.body).extract[PatchNodesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
//    logger.trace("PATCH /orgs/"+orgid+"/nodes/"+bareId+" input: "+node.toString)
    val resp = response
    val hashedPw = if (node.token.isDefined) Password.hash(node.token.get) else ""    // hash the token if that is what is being updated
    val (action, attrName) = node.getDbUpdate(id, hashedPw)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("no.valid.note.attr.specified")))
    val patValidateAction = if (attrName == "pattern" && node.pattern.get != "") PatternsTQ.getPattern(node.pattern.get).length.result else DBIO.successful(1)
    val (valServiceIdActions, svcRefs) = if (attrName == "userInput") NodesTQ.validateServiceIds(node.userInput.get)
      else (DBIO.successful(Vector()), Vector())
    db.run(patValidateAction.asTry.flatMap({ xs =>
      // Check if pattern exists, then get services referenced
      logger.debug("PATCH /orgs/"+orgid+"/nodes/"+bareId+" pattern validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0) valServiceIdActions.asTry
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("pattern.not.in.exchange"))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      // Check if referenced services exist, then get whether node is using policy
      logger.debug("PATCH /orgs/"+orgid+"/nodes"+bareId+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) NodesTQ.getNodeUsingPolicy(id).result.asTry
          else {
            val errStr = if (invalidIndex < svcRefs.length) ExchangeMessage.translateMessage("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
            else ExchangeMessage.translateMessage("service.not.in.exchange.index", Nth(invalidIndex+1))
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      // Check if node is using policy, then update node
      logger.debug("PATCH /orgs/"+orgid+"/nodes/"+bareId+" policy related attrs: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {
          val (existingPattern, existingPublicKey) = v.head
          if (node.pattern.getOrElse("")!="" && existingPattern=="" && existingPublicKey!="") DBIO.failed(new Throwable(ExchangeMessage.translateMessage("not.pattern.when.policy"))).asTry
          else action.transactionally.asTry   // they are not trying to switch from policy to pattern, so we can continue
        }
        else action.transactionally.asTry    // node doesn't exit yet, we can continue
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (node.token.isDefined) AuthCache.putNode(id, hashedPw, node.token.get)  // We do not need to run putOwner because patch does not change the owner
              //AuthCache.ids.putNode(id, hashedPw, node.token.get)
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.attribute.updated", attrName, id))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", id))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("unexpected.result.from.update", e)) }
          //          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("node.not.inserted.or.updated", id, t.getMessage))
      }
    })
  })

  // =========== POST /orgs/{orgid}/nodes/{id}/services_configstate ===============================
  val postNodesConfigstate =
    (apiOperation[ApiResponse]("postNodesConfigstate")
      summary "Changes config state of registered services"
      description """Suspends (or resumes) 1 or more services on this edge node. Can be run by the node owner or the node. The **request body** structure:

```
{
  "org": "myorg",    // the org of services to be modified, or empty string for all orgs
  "url": "myserviceurl"       // the url of services to be modified, or empty string for all urls
  "configState": "suspended"   // or "active"
}
```
      """
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be modified."), paramType = ParamType.Path),
      Parameter("body", DataType[PostNodeConfigStateRequest],
        Option[String]("Service selection and desired state. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postNodesConfigState2 = (apiOperation[PostNodeConfigStateRequest]("postNodesConfigstate2") summary("a") description("a"))

  post("/orgs/:orgid/nodes/:id/services_configstate", operation(postNodesConfigstate)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val nodeId = OrgAndId(orgid,bareId).toString
    val configStateReq = try { parse(request.body).extract[PostNodeConfigStateRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    configStateReq.validate()
    val resp = response

    db.run(NodesTQ.getRegisteredServices(nodeId).result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/configstate result: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) configStateReq.getDbUpdate(v.head, nodeId).asTry   // pass the update action to the next step
          else DBIO.failed(new Throwable("Invalid Input: node "+nodeId+" not found")).asTry    // it seems this returns success even when the node is not found
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step. Is this necessary, or will flatMap do that automatically?
      }

    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/configstate write row result: "+xs.toString)
      xs match {
        case Success(i) => //try {     // i comes to us as type Any
          if (i.toString.toInt > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.services.updated", nodeId))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", nodeId))
          }
          //} catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("node.not.inserted.or.updated", nodeId, t.getMessage))
      }
    })

  })

  // =========== DELETE /orgs/{orgid}/nodes/{id} ===============================
  val deleteNodes =
    (apiOperation[ApiResponse]("deleteNodes")
      summary "Deletes a node"
      description "Deletes a node (RPi) from the exchange DB, and deletes the agreements stored for this node (but does not actually cancel the agreements between the node and agbots). Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id", operation(deleteNodes)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(NodesTQ.getNode(id).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.removeNodeAndOwner(id)
            //AuthCache.ids.removeOne(id)
            //AuthCache.nodesOwner.removeOne(id)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", id))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.not.deleted", id, t.toString))
        }
    })
  })

  // =========== POST /orgs/{orgid}/nodes/{id}/heartbeat ===============================
  val postNodesHeartbeat =
    (apiOperation[ApiResponse]("postNodesHeartbeat")
      summary "Tells the exchange this node is still operating"
      description "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  post("/orgs/:orgid/nodes/:id/heartbeat", operation(postNodesHeartbeat)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodesTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/heartbeat result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {       // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", id))
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.not.updated", id, t.toString))
        }
    })
  })


  /* ====== GET /orgs/{orgid}/nodes/{id}/errors ================================ */
  val getNodeError =
    (apiOperation[NodeError]("getNodeError")
      summary("Returns the node error")
      description("""Returns any node errors.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id/errors", operation(getNodeError)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
      db.run(NodeErrorTQ.getNodeError(id).result).map({ list =>
        logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/errors result size: "+list.size)
        if (list.nonEmpty) {
          resp.setStatus(HttpCode.OK)
          list.head.toNodeError
        }
        else resp.setStatus(HttpCode.NOT_FOUND)
      })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id}/errors ===============================
  val putNodeError =
    (apiOperation[ApiResponse]("putNodeError")
      summary "Adds/updates node error list"
      description """Adds or updates any error of a node. This is called by the node or owning user. The **request body** structure:

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
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node wanting to add/update the error."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutNodeErrorRequest],
          Option[String]("Error object add or update. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putNodeError2 = (apiOperation[PutNodeErrorRequest]("putNodeError2") summary("a") description("a"))

  put("/orgs/:orgid/nodes/:id/errors", operation(putNodeError)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val error = try { parse(request.body).extract[PutNodeErrorRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    error.validate()
    val resp = response
    db.run(error.toNodeErrorRow(id).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/errors result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.errors.added"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.errors.not.inserted", id, t.getMessage))
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.errors.not.inserted", id, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/errors ===============================
  val deleteNodeError =
    (apiOperation[ApiResponse]("deleteNodeError")
      summary "Deletes the error list of a node"
      description "Deletes the error list of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node for which the error is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id/errors", operation(deleteNodeError)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeErrorTQ.getNodeError(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/errors result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.errors.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.errors.not.found", id))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.errors.not.deleted", id, t.toString))
      }
    })
  })

  /* ====== GET /orgs/{orgid}/nodes/{id}/status ================================ */
  val getNodeStatus =
    (apiOperation[NodeStatus]("getNodeStatus")
      summary("Returns the node status")
      description("""Returns the node run time status, for example service container status. Can be run by a user or the node.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id/status", operation(getNodeStatus)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodeStatusTQ.getNodeStatus(id).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/status result size: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.OK)
        list.head.toNodeStatus
      }
      else resp.setStatus(HttpCode.NOT_FOUND)
    })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id}/status ===============================
  val putNodeStatus =
    (apiOperation[ApiResponse]("putNodeStatus")
      summary "Adds/updates the node status"
      description """Adds or updates the run time status of a node. This is called by the node or owning user. The **request body** structure:

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
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node wanting to add/update this status."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PutNodeStatusRequest],
        Option[String]("Status object add or update. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putNodeStatus2 = (apiOperation[PutNodeStatusRequest]("putNodeStatus2") summary("a") description("a"))  // for some bizarre reason, the PutNodeStatusRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id/status", operation(putNodeStatus)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val status = try { parse(request.body).extract[PutNodeStatusRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    status.validate()
    val resp = response
    db.run(status.toNodeStatusRow(id).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/status result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("status.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.status.not.inserted.or.updated", id, t.getMessage))
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.status.not.inserted.or.updated", id, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/status ===============================
  val deleteNodeStatus =
    (apiOperation[ApiResponse]("deleteNodeStatus")
      summary "Deletes the status of a node"
      description "Deletes the status of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node for which the status is to be deleted."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id/status", operation(deleteNodeStatus)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeStatusTQ.getNodeStatus(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/status result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.status.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.status.not.found", id))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.status.not.deleted", id, t.toString))
      }
    })
  })

  /* ====== GET /orgs/{orgid}/nodes/{id}/policy ================================ */
  val getNodePolicy =
    (apiOperation[NodePolicy]("getNodePolicy")
      summary("Returns the node policy")
      description("""Returns the node policy. Can be run by a user or the node.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"post ok"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"acess denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id/policy", operation(getNodePolicy)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodePolicyTQ.getNodePolicy(id).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/policy result size: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.OK)
        list.head.toNodePolicy
      }
      else resp.setStatus(HttpCode.NOT_FOUND)
    })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id}/policy ===============================
  val putNodePolicy =
    (apiOperation[ApiResponse]("putNodePolicy")
      summary "Adds/updates the node policy"
      description """Adds or updates the policy of a node. This is called by the node or owning user. The **request body** structure:

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
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node wanting to add/update this policy."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PutNodePolicyRequest],
        Option[String]("Policy object add or update. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putNodePolicy2 = (apiOperation[PutNodePolicyRequest]("putNodePolicy2") summary("a") description("a"))  // for some bizarre reason, the PutNodePolicyRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id/policy", operation(putNodePolicy)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val policy = try { parse(request.body).extract[PutNodePolicyRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    policy.validate()
    val resp = response
    db.run(policy.toNodePolicyRow(id).upsert.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/policy result: "+xs.toString)
      xs match {
        case Success(_) => NodesTQ.setLastHeartbeat(id, ApiTime.nowUTC).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("Update /orgs/"+orgid+"/nodes/"+bareId+" lastHeartbeat result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.policy.added.or.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", id))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.policy.not.updated", id, e)) }    // the specific exception is NumberFormatException
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.policy.not.inserted.or.updated", id, t.getMessage))
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.policy.not.inserted.or.updated", id, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/policy ===============================
  val deleteNodePolicy =
    (apiOperation[ApiResponse]("deleteNodePolicy")
      summary "Deletes the policy of a node"
      description "Deletes the policy of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node for which the policy is to be deleted."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id/policy", operation(deleteNodePolicy)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodePolicyTQ.getNodePolicy(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/policy result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.policy.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.policy.not.found", id))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.policy.not.deleted", id, t.toString))
      }
    })
  })


  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements ================================ */
  val getNodeAgreements =
    (apiOperation[GetNodeAgreementsResponse]("getNodeAgreements")
      summary("Returns all agreements this node is in")
      description("""Returns all agreements in the exchange DB that this node is part of. Can be run by a user or the node.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      )

  get("/orgs/:orgid/nodes/:id/agreements", operation(getNodeAgreements)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreements(id).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/agreements result size: "+list.size)
      val agreements = new MutableHashMap[String, NodeAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agId, e.toNodeAgreement) }
      if (agreements.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodeAgreementsResponse(agreements.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements/{agid} ================================ */
  val getOneNodeAgreement =
    (apiOperation[GetNodeAgreementsResponse]("getOneNodeAgreement")
      summary("Returns an agreement for a node")
      description("""Returns the agreement with the specified agid for the specified node id in the exchange DB. Can be run by a user or the node.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id/agreements/:agid", operation(getOneNodeAgreement)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val agId = params("agid")
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreement(id, agId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+list.toString)
      val agreements = new MutableHashMap[String, NodeAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agId, e.toNodeAgreement) }
      if (agreements.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodeAgreementsResponse(agreements.toMap, 0)
    })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  val putNodeAgreement =
    (apiOperation[ApiResponse]("putNodeAgreement")
      summary "Adds/updates an agreement of a node"
      description """Adds a new agreement of a node to the exchange DB, or updates an existing agreement. This is called by the
        node or owning user to give their information about the agreement. The **request body** structure:

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
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node wanting to add/update this agreement."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutNodeAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putNodeAgreement2 = (apiOperation[PutNodeAgreementRequest]("putAgreement2") summary("a") description("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id/agreements/:agid", operation(putNodeAgreement)) ({
    //todo: keep a running total of agreements for each MS so we can search quickly for available MSs
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val agId = params("agid")
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val agreement = try { parse(request.body).extract[PutNodeAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    agreement.validate()
    val resp = response
    db.run(NodeAgreementsTQ.getNumOwned(id).result.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" num owned: "+xs)
      val numOwned = xs
      val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
      if (maxAgreements == 0 || numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        agreement.toNodeAgreementRow(id, agId).upsert.asTry
      }
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("over.limit.of.agreements.for.node", maxAgreements))).asTry
    }).flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(_) => NodesTQ.setLastHeartbeat(id, ApiTime.nowUTC).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("Update /orgs/"+orgid+"/nodes/"+bareId+" lastHeartbeat result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.agreement.added.or.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.not.found", id))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.agreement.not.updated", id, e)) }    // the specific exception is NumberFormatException
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.agreement.not.inserted.or.updated", agId, id, t.getMessage))
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.agreement.not.inserted.or.updated", agId, id, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements ===============================
  val deleteNodeAllAgreement =
    (apiOperation[ApiResponse]("deleteNodeAllAgreement")
      summary "Deletes all agreements of a node"
      description "Deletes all of the current agreements of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/nodes/:id/agreements", operation(deleteNodeAllAgreement)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreements(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/agreements result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.agreements.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("no.node.agreements.found", id))
          //            ApiResponse(ApiResponseType.NOT_FOUND, "no agreements for node '"+id+"' found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.agreements.not.deleted", id, t.toString))
        }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  val deleteNodeAgreement =
    (apiOperation[ApiResponse]("deleteNodeAgreement")
      summary "Deletes an agreement of a node"
      description "Deletes an agreement of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id/agreements/:agid", operation(deleteNodeAgreement)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val agId = params("agid")
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreement(id,agId).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it  or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.agreement.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.agreement.not.found", agId, id))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.agreement.not.deleted", agId, id, t.toString))
        }
    })
  })

  // =========== POST /orgs/{orgid}/nodes/{id}/msgs ===============================
  val postNodesMsgs =
    (apiOperation[ApiResponse]("postNodesMsgs")
      summary "Sends a msg from an agbot to a node"
      description """Sends a msg from an agbot to a node. The agbot must 1st sign the msg (with its private key) and then encrypt the msg (with the node's public key). Can be run by any agbot. The **request body** structure:

```
{
  "message": "VW1RxzeEwTF0U7S96dIzSBQ/hRjyidqNvBzmMoZUW3hpd3hZDvs",    // msg to be sent to the node
  "ttl": 86400       // time-to-live of this msg, in seconds
}
```
      """
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to send a msg to."), paramType = ParamType.Path),
        // Agbot id/token must be in the header
        Parameter("body", DataType[PostNodesMsgsRequest],
          Option[String]("Signed/encrypted message to send to the node. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postNodesMsgs2 = (apiOperation[PostNodesMsgsRequest]("postNodesMsgs2") summary("a") description("a"))

  post("/orgs/:orgid/nodes/:id/msgs", operation(postNodesMsgs)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val nodeId = OrgAndId(orgid,bareId).toString
    val ident = authenticate().authorizeTo(TNode(nodeId),Access.SEND_MSG_TO_NODE)
    val agbotId = ident.creds.id
    val msg = try { parse(request.body).extract[PostNodesMsgsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    val resp = response
    // Remove msgs whose TTL is past, then check the mailbox is not full, then get the agbot publicKey, then write the nodemsgs row, all in the same db.run thread
    db.run(NodeMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs delete expired result: "+xs.toString)
      NodeMsgsTQ.getNumOwned(nodeId).result
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs mailbox size: "+xs)
      val mailboxSize = xs
      val maxMessagesInMailbox = ExchConfig.getInt("api.limits.maxMessagesInMailbox")
      if (maxMessagesInMailbox == 0 || mailboxSize < maxMessagesInMailbox) AgbotsTQ.getPublicKey(agbotId).result.asTry
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("node.mailbox.full", nodeId, maxMessagesInMailbox))).asTry
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs agbot publickey result: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {    // it seems this returns success even when the agbot is not found
            val agbotPubKey = v.head
            if (agbotPubKey != "") NodeMsgRow(0, nodeId, agbotId, agbotPubKey, msg.message, ApiTime.nowUTC, ApiTime.futureUTC(msg.ttl)).insert.asTry
            else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("message.sender.public.key.not.in.exchange"))).asTry
          }
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("invalid.input.agbot.not.found", agbotId))).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs write row result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.msg.inserted", v))
        case Failure(t) => if (t.getMessage.startsWith("Invalid Input:")) {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("node.msg.not.inserted", nodeId, t.getMessage))
          } else if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("node.msg.not.inserted", nodeId, t.getMessage))
          } else if (t.getMessage.contains("is not present in table")) {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.msg.nodeid.not.found", nodeId, t.getMessage))
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.msg.not.inserted", nodeId, t.toString))
          }
        }
    })
  })

  /* ====== GET /orgs/{orgid}/nodes/{id}/msgs ================================ */
  val getNodeMsgs =
    (apiOperation[GetNodeMsgsResponse]("getNodeMsgs")
      summary("Returns all msgs sent to this node")
      description("""Returns all msgs that have been sent to this node. They will be returned in the order they were sent. All msgs that have been sent to this node will be returned, unless the node has deleted some, or some are past their TTL. Can be run by a user or the node.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node."), paramType=ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/nodes/:id/msgs", operation(getNodeMsgs)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    // Remove msgs whose TTL is past, and then get the msgs for this node
    db.run(NodeMsgsTQ.getMsgsExpired.delete.flatMap({ xs =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/msgs delete expired result: "+xs.toString)
      NodeMsgsTQ.getMsgs(id).result
    })).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/msgs result size: "+list.size)
      val listSorted = list.sortWith(_.msgId < _.msgId)
      val msgs = new ListBuffer[NodeMsg]
      if (listSorted.nonEmpty) for (m <- listSorted) { msgs += m.toNodeMsg }
      if (msgs.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodeMsgsResponse(msgs.toList, 0)
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/msgs/{msgid} ===============================
  val deleteNodeMsg =
    (apiOperation[ApiResponse]("deleteNodeMsg")
      summary "Deletes an msg of a node"
      description "Deletes an msg that was sent to a node. This should be done by the node after each msg is read. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (nodeid) of the node to be deleted."), paramType = ParamType.Path),
        Parameter("msgid", DataType.String, Option[String]("ID of the msg to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/nodes/:id/msgs/:msgid", operation(deleteNodeMsg)) ({
    val orgid = params("orgid")
    val bareId = params("id")
    val id = OrgAndId(orgid,bareId).toString
    val msgId = try { params("msgid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("msgid.must.be.int", e))) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeMsgsTQ.getMsg(id,msgId).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/msgs/"+msgId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("node.msg.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("node.msg.not.found", msgId, id))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("node.msg.not.deleted", msgId, id, t.toString))
        }
    })
  })

}
