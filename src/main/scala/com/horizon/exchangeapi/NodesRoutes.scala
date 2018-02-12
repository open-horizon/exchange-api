/** Services routes for all of the /orgs/{orgid}/nodes api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap, Set => MutableSet}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/nodes routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/nodes */
case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int)
case class GetNodeAttributeResponse(attribute: String, value: String)

/** Input for pattern-based search for nodes to make agreements with. */
case class PostPatternSearchRequest(workloadUrl: String, secondsStale: Int, startIndex: Int, numEntries: Int) {
  def validate() = {}
}

// Tried this to have names on the tuple returned from the db, but didn't work...
//case class PatternSearchDbResponse(id: Rep[String], msgEndPoint: Rep[String], publicKey: Rep[String], workloadUrl: Rep[Option[String]], state: Rep[Option[String]])
case class PatternSearchHashElement(msgEndPoint: String, publicKey: String, noAgreementYet: Boolean)

case class PatternNodeResponse(id: String, msgEndPoint: String, publicKey: String)
case class PostPatternSearchResponse(nodes: List[PatternNodeResponse], lastIndex: Int)


case class PostNodeHealthRequest(lastTime: String) {
  def validate() = {}
}

case class NodeHealthAgreementElement(lastUpdated: String)
class NodeHealthHashElement(var lastHeartbeat: String, var agreements: Map[String,NodeHealthAgreementElement])
case class PostNodeHealthResponse(nodes: Map[String,NodeHealthHashElement])


/** Input for microservice-based (citizen scientist) search, POST /orgs/"+orgid+"/search/nodes */
case class PostSearchNodesRequest(desiredMicroservices: List[RegMicroserviceSearch], secondsStale: Int, propertiesToReturn: List[String], startIndex: Int, numEntries: Int) {
  /** Halts the request with an error msg if the user input is invalid. */
  def validate() = {
    for (m <- desiredMicroservices) {
      m.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  /** Returns the microservices that match all of the search criteria */
  def matches(nodes: Map[String,Node], agHash: AgreementsHash)(implicit logger: Logger): PostSearchNodesResponse = {
    // logger.trace(agHash.agHash.toString)

    // Loop thru the existing nodes and microservices in the DB. (Should probably make this more FP style)
    var nodesResp: List[NodeResponse] = List()
    // for ((id,d) <- nodes; if !ApiTime.isSecondsStale(d.lastHeartbeat,secondsStale) ) {   <-- the db query now filters out stale nodes
    for ((id,d) <- nodes) {
      // Get all microservices for this node that are not max'd out on agreements
      var availableMicros: List[RegMicroservice] = List()
      for (m <- d.registeredMicroservices) { breakable {
        // do not even bother checking this against the search criteria if this micro is already at its agreement limit
        val agNode = agHash.agHash.get(id)
        agNode match {
          case Some(agNode2) => val agNum = agNode2.get(m.url)
            agNum match {
              case Some(agNum2) => if (agNum2 >= m.numAgreements) break  // this is really a continue
              case None => ;      // no agreements for this microservice, nothing to do
            }
          case None => ;      // no agreements for this node, nothing to do
        }
        availableMicros = availableMicros :+ m
      } }

      // We now have several microservices for 1 node from the db (that are not max'd out on agreements). See if all of the desired micros are satisfied.
      var microsResp: List[RegMicroservice] = List()
      breakable { for (desiredMicro <- desiredMicroservices) {
        var found: Boolean = false
        breakable { for (availableMicro <- availableMicros) {
          if (desiredMicro.matches(availableMicro)) {
            microsResp = microsResp :+ availableMicro
            found = true
            break
          }
        } }
        if (!found) break     // we did not find one of the required micros, so end early
      } }

      if (microsResp.length == desiredMicroservices.length) {
        // all required micros were available in this node, so add this node to the response list
        nodesResp = nodesResp :+ NodeResponse(id, d.name, microsResp, d.msgEndPoint, d.publicKey)
      }
    }
    // return the search result to the rest client
    PostSearchNodesResponse(nodesResp, 0)
  }
}

case class NodeResponse(id: String, name: String, microservices: List[RegMicroservice], msgEndPoint: String, publicKey: String)
case class PostSearchNodesResponse(nodes: List[NodeResponse], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/<node-id> */
case class PutNodesRequest(token: String, name: String, pattern: String, registeredMicroservices: List[RegMicroservice], msgEndPoint: String, softwareVersions: Map[String,String], publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Halts the request with an error msg if the user input is invalid. */
  def validate() = {
    // if (msgEndPoint == "" && publicKey == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either msgEndPoint or publicKey must be specified."))  <-- skipping this check because POST /agbots/{id}/msgs checks for the publicKey
    if (token == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the token specified must not be blank"))
    if (pattern != "" && """.*/.*""".r.findFirstIn(pattern).isEmpty) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the 'pattern' attribute must have the orgid prepended, with a slash separating"))
    for (m <- registeredMicroservices) {
      // now we support more than 1 agreement for a MS
      // if (m.numAgreements != 1) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value "+m.numAgreements+" for numAgreements in "+m.url+". Currently it must always be 1."))
      m.validate match {
        case Some(s) => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, s))
        case None => ;
      }
    }
  }

  /** Get the db actions to insert or update all parts of the node */
  def getDbUpsert(id: String, orgid: String, owner: String): DBIO[_] = {
    // Accumulate the actions in a list, starting with the action to insert/update the node itself
    val actions = ListBuffer[DBIO[_]](NodeRow(id, orgid, token, name, owner, pattern, msgEndPoint, write(softwareVersions), ApiTime.nowUTC, publicKey).upsert)
    val microsUrls = MutableSet[String]()    // the url attribute of the micros we are creating, so we can delete everthing else for this node
    val propsIds = MutableSet[String]()    // the propId attribute of the props we are creating, so we can delete everthing else for this node
    // Now add actions to insert/update the node's micros and props
    for (m <- registeredMicroservices) {
      actions += m.toRegMicroserviceRow(id).upsert
      microsUrls += m.url
      for (p <- m.properties) {
        actions += p.toPropRow(id,m.url).upsert
        propsIds += id+"|"+m.url+"|"+p.name
      }
    }
    // handle the case where they changed what micros or props this node has, form selects to delete all micros and props that reference this node that aren't in microsUrls, propsIds
    actions += PropsTQ.rows.filter(_.propId like id+"|%").filterNot(_.propId inSet propsIds).delete     // props that reference this node, but are not in the list we just created/updated
    actions += RegMicroservicesTQ.rows.filter(_.nodeId === id).filterNot(_.url inSet microsUrls).delete    // micros that reference this node, but are not in the list we just created/updated

    DBIO.seq(actions.toList: _*)      // convert the list of actions to a DBIO seq
  }

  /** Get the db actions to update all parts of the node. This is run, instead of getDbUpsert(), when it is a node doing it,
   * because we can't let a node create new nodes. */
  def getDbUpdate(id: String, orgid: String, owner: String): DBIO[_] = {
    val actions = ListBuffer[DBIO[_]](NodeRow(id, orgid, token, name, owner, pattern, msgEndPoint, write(softwareVersions), ApiTime.nowUTC, publicKey).update)
    val microsUrls = MutableSet[String]()    // the url attribute of the micros we are updating, so we can delete everthing else for this node
    val propsIds = MutableSet[String]()    // the propId attribute of the props we are updating, so we can delete everthing else for this node
    for (m <- registeredMicroservices) {
      actions += m.toRegMicroserviceRow(id).upsert     // only the node should be update (the rest should be upsert), because that's the only one we know exists
      microsUrls += m.url
      for (p <- m.properties) {
        actions += p.toPropRow(id,m.url).upsert
        propsIds += id+"|"+m.url+"|"+p.name
      }
    }
    // handle the case where they changed what micros or props this node has, form selects to delete all micros and props that reference this node that aren't in microsUrls, propsIds
    actions += PropsTQ.rows.filter(_.propId like id+"|%").filterNot(_.propId inSet propsIds).delete     // props that reference this node, but are not in the list we just updated
    actions += RegMicroservicesTQ.rows.filter(_.nodeId === id).filterNot(_.url inSet microsUrls).delete    // micros that reference this node, but are not in the list we just updated

    DBIO.seq(actions.toList: _*)
  }

  /** Not used any more, kept for reference of how to access object store - Returns the microservice templates for the registeredMicroservices in this object
  def getMicroTemplates: Map[String,String] = {
    if (ExchConfig.getBoolean("api.microservices.disable")) return Map[String,String]()
    val resp = new MutableHashMap[String, String]()
    for (m <- registeredMicroservices) {
      // parse the microservice name out of the specRef url
      val R = ExchConfig.getString("api.specRef.prefix")+"(.*)"+ExchConfig.getString("api.specRef.suffix")+"/?"
      val R2 = R.r
      val microName = m.url match {
        case R2(mNname) => mNname
        case _ => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Incorrect format for microservice url '"+m.url+"'"))
      }

      // find arch and version properties
      val arch = m.properties.find(p => p.name=="arch").map[String](p => p.value).orNull
      if (arch == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Arch property is not specified for microservice '"+m.url+"'"))
      val version = m.properties.find(p => p.name=="version").map[String](p => p.value).orNull
      if (version == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Version property is not specified for microservice '"+m.url+"'"))
      val versObj = Version(version)

      // Get the microservice template from softlayer object store
      val microTmplName = microName+"-"+arch+"-"+versObj
      val objStoreUrl = ExchConfig.getString("api.objStoreTmpls.prefix")+"/"+ExchConfig.getString("api.objStoreTmpls.microDir")+"/"+microTmplName+ExchConfig.getString("api.objStoreTmpls.suffix")
      var response: HttpResponse[String] = null
      try {     // the http request can throw java.net.SocketTimeoutException: connect timed out
        response = scalaj.http.Http(objStoreUrl).headers(("Accept","application/json")).asString
      } catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Exception thrown while trying to get '"+objStoreUrl+"' from Softlayer object storage: "+e)) }
      if (response.code != HttpCode.OK) halt(response.code, ApiResponse(ApiResponseType.BAD_INPUT, "Microservice template for '"+microTmplName+"' not found"))
      resp.put(m.url, response.body)
    }
    resp.toMap
  }
  */
}

case class PatchNodesRequest(token: Option[String], name: Option[String], pattern: Option[String], msgEndPoint: Option[String], softwareVersions: Option[Map[String,String]], publicKey: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the node, and the attribute name being updated. */
  def getDbUpdate(id: String): (DBIO[_],String) = {
    val lastHeartbeat = ApiTime.nowUTC
    //todo: support updating more than 1 attribute, but i think slick does not support dynamic db field names
    // find the 1st non-blank attribute and create a db action to update it for this node
    token match {
      case Some(token2) => if (token2 == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the token can not be set to the empty string"))
        val tok = if (Password.isHashed(token2)) token2 else Password.hash(token2)
        return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, tok, lastHeartbeat)), "token")
      case _ => ;
    }
    softwareVersions match {
      case Some(swv) => val swVersions = if (swv.nonEmpty) write(softwareVersions) else ""
        return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.softwareVersions,d.lastHeartbeat)).update((id, swVersions, lastHeartbeat)), "softwareVersions")
      case _ => ;
    }
    name match { case Some(name2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name2, lastHeartbeat)), "name"); case _ => ; }
    pattern match { case Some(pattern2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.pattern,d.lastHeartbeat)).update((id, pattern2, lastHeartbeat)), "pattern"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint2, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey2) => return ((for { d <- NodesTQ.rows if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey2, lastHeartbeat)), "publicKey"); case _ => ; }
    return (null, null)
  }
}


case class PutNodeStatusRequest(connectivity: Map[String,Boolean], microservices: List[OneMicroservice], workloads: List[OneWorkload]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toNodeStatusRow(nodeId: String) = NodeStatusRow(nodeId, write(connectivity), write(microservices), write(workloads), ApiTime.nowUTC)
}


/** Output format for GET /orgs/{orgid}/nodes/{id}/agreements */
case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)

/** Input format for PUT /orgs/{orgid}/nodes/{id}/agreements/<agreement-id> */
case class PutNodeAgreementRequest(microservices: List[NAMicroservice], workload: NAWorkload, state: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toNodeAgreement = NodeAgreement(microservices, workload, state, ApiTime.nowUTC)
  def toNodeAgreementRow(nodeId: String, agId: String) = NodeAgreementRow(agId, nodeId, write(microservices), workload.orgid, workload.pattern, workload.url, state, ApiTime.nowUTC)
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
      description("""Returns all nodes (RPis) in the exchange DB. Can be run by a user or agbot (but not a node).

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      // authorizations("basicAuth")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include nodes with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("name", DataType.String, Option[String]("Filter results to only include nodes with this name (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include nodes with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      // this does not work, because scalatra will not give me the request.body on a GET
      // parameters(Parameter("body", DataType[GetNodeRequest], Option[String]("Node search criteria"), paramType = ParamType.Body))
      )

  /** operation() is a method of org.scalatra.swagger.SwaggerSupport that takes SwaggerOperation and returns RouteTransformer */
  get("/orgs/:orgid/nodes", operation(getNodes)) ({
    // try {    // this try/catch does not get us much more than what scalatra does by default
    // I think the request member is of type org.eclipse.jetty.server.Request, which implements interfaces javax.servlet.http.HttpServletRequest and javax.servlet.ServletRequest
    val orgid = swaggerHack("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    // throw new IllegalArgumentException("arg 1 was wrong...")
    // The nodes, microservices, and properties tables all combine to form the Node object, so we do joins to get them all.
    // Note: joinLeft is necessary here so that if no micros exist for a node, we still get the node (and likewise for the micro if no props exist).
    //    This means m and p below are wrapped in Option because they may not always be there
    var q = for {
      // ((d, m), p) <- NodesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on ( (dm, p) => { dm._1.id === p.nodeId && dm._2.map(_.url) === p.msUrl } )
      ((d, m), p) <- NodesTQ.getAllNodes(orgid) joinLeft RegMicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
    } yield (d, m, p)

    // add filters
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_._1.id like id) else q = q.filter(_._1.id === id) })
    params.get("name").foreach(name => { if (name.contains("%")) q = q.filter(_._1.name like name) else q = q.filter(_._1.name === name) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_._1.owner like owner) else q = q.filter(_._1.owner === owner) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes result size: "+list.size)
      val nodes = NodesTQ.parseJoin(superUser, list)
      if (list.isEmpty) resp.setStatus(HttpCode.NOT_FOUND)
      GetNodesResponse(nodes, 0)
    })
    // } catch { case e: Exception => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "Oops! Somthing unexpected happened: "+e)) }
  })

  /* ====== GET /orgs/{orgid}/nodes/{id} ================================ */
  val getOneNode =
    (apiOperation[GetNodesResponse]("getOneNode")
      summary("Returns a node")
      description("""Returns the node (RPi) with the specified id in the exchange DB. Can be run by that node, a user, or an agbot.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified, and it must be 1 of the direct attributes of the node resource (not of the microservices). If not specified, the entire node resource (including microservices) will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/nodes/:id", operation(getOneNode)) ({
    val orgid = params("orgid")
    val bareId = swaggerHack("id")
    val id = OrgAndId(orgid,bareId).toString
    val ident = credsAndLog().authenticate().authorizeTo(TNode(id),Access.READ)
    val superUser = ident.isSuperUser
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the node
        val q = NodesTQ.getAttribute(id, attribute)
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Node attribute name '"+attribute+"' is not an attribute of the node resource."))
        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+" attribute result: "+list.size)
          if (list.nonEmpty) {
            GetNodeAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")     // validateAccessToNode() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })

      case None => ;  // Return the whole node, including the microservices
        // The nodes, microservices, and properties tables all combine to form the Node object, so we do joins to get them all.
        // Note: joinLeft is necessary here so that if no micros exist for a node, we still get the node (and likewise for the micro if no props exist).
        //    This means m and p below are wrapped in Option because sometimes they may not always be there
        val q = for {
          // (((d, m), p), s) <- NodesTQ.rows joinLeft MicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId) joinLeft SoftwareVersionsTQ.rows on (_._1._1.id === _.nodeId)
          ((d, m), p) <- NodesTQ.rows joinLeft RegMicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
          if d.id === id
        } yield (d, m, p)

        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+" result: "+list.size)
          if (list.nonEmpty) {
            val nodes = NodesTQ.parseJoin(superUser, list)
            GetNodesResponse(nodes, 0)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")     // validateAccessToNode() will return ApiResponseType.NOT_FOUND to the client so do that here for consistency
          }
        })
    }
  })

  // ======== POST /org/{orgid}/patterns/{pat-id}/search ========================
  val postPatternSearch =
    (apiOperation[PostPatternSearchResponse]("postPatternSearch")
      summary("Returns matching nodes of a particular pattern")
      description """Returns the matching nodes that are this pattern and do not already have an agreement for the specified workload. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "workloadUrl": "https://bluehorizon.network/workloads/sdr",
  "secondsStale": 60,     // max number of seconds since the exchange has heard from the node, 0 if you do not care
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPatternSearchResponse],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postPatternSearch2 = (apiOperation[PostPatternSearchResponse]("postPatternSearch2") summary("a") description("a"))

  /** Normally called by the agbot to search for available nodes. */
  post("/orgs/:orgid/patterns/:pattern/search", operation(postPatternSearch)) ({
    val orgid = swaggerHack("orgid")
    val pattern = params("pattern")   // but do not have a hack/fix for the name
    val compositePat = OrgAndId(orgid,pattern).toString
    credsAndLog().authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostPatternSearchRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search criteria: "+searchProps.toString)
    val resp = response
    /*
      Narrow down the db query results as much as possible with by joining the Nodes and NodeAgreements tables and filtering.
      In english, the join gets: n.id, n.msgEndPoint, n.publicKey, n.lastHeartbeat, a.workloadUrl, a.state
      The filter is: n.pattern==ourpattern && a.state!=""
      Then we have to go thru all of the results and find nodes that do NOT have an agreement for ourworkload.
      Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
    */
    val oldestTime = if (searchProps.secondsStale > 0) ApiTime.pastUTC(searchProps.secondsStale) else ApiTime.beginningUTC
    val q = for {
      (n, a) <- NodesTQ.rows.filter(_.orgid === orgid).filter(_.pattern === compositePat).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
    } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.workloadUrl), a.map(_.state))

    db.run(q.result).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search result size: "+list.size)
      logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/search result: "+list.toString)
      if (list.nonEmpty) {
        // Go thru the rows and build a hash of the nodes that do NOT have an agreement for our workload
        val nodeHash = new MutableHashMap[String,PatternSearchHashElement]     // key is node id, value noAgreementYet which is true if so far we haven't hit an agreement for our workload for this node
        for ( (id, msgEndPoint, publicKey, workloadUrlOption, stateOption) <- list ) {
          //logger.trace("id: "+id+", workloadUrlOption: "+workloadUrlOption.getOrElse("")+", searchProps.workloadUrl: "+searchProps.workloadUrl+", stateOption: "+stateOption.getOrElse(""))
          nodeHash.get(id) match {
            case Some(_) => if (workloadUrlOption.getOrElse("") == searchProps.workloadUrl && stateOption.getOrElse("") != "") { /*logger.trace("setting to false");*/ nodeHash.put(id, PatternSearchHashElement(msgEndPoint, publicKey, noAgreementYet = false)) }  // this is no longer a candidate
            case None => val noAgr = if (workloadUrlOption.getOrElse("") == searchProps.workloadUrl && stateOption.getOrElse("") != "") false else true
              nodeHash.put(id, PatternSearchHashElement(msgEndPoint, publicKey, noAgr))   // this node id not in the hash yet, and it and start it out true
          }
        }
        // Convert our hash to the list response of the rest api
        //val respList = list.map( x => PatternNodeResponse(x._1, x._2, x._3)).toList
        val respList = new ListBuffer[PatternNodeResponse]
        for ( (k, v) <- nodeHash) if (v.noAgreementYet) respList += PatternNodeResponse(k, v.msgEndPoint, v.publicKey)
        if (respList.nonEmpty) resp.setStatus(HttpCode.POST_OK)
        else resp.setStatus(HttpCode.NOT_FOUND)
        PostPatternSearchResponse(respList.toList, 0)
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        PostPatternSearchResponse(List[PatternNodeResponse](), 0)
      }
    })
  })

  /** From the given db joined node/agreement rows, build the output node health hash and return it.
     This is shared between POST /org/{orgid}/patterns/{pat-id}/nodehealth and POST /org/{orgid}/search/nodehealth
    */
  def buildNodeHealthHash(list: scala.Seq[(String, String, Option[String], Option[String])]): Map[String,NodeHealthHashElement] = {
    // Go thru the rows and build a hash of the nodes, adding the agreement to its value as we encounter them
    val nodeHash = new MutableHashMap[String,NodeHealthHashElement]     // key is node id, value has lastHeartbeat and the agreements map
    for ( (nodeId, lastHeartbeat, agrId, agrLastUpdated) <- list ) {
      //logger.trace("id: "+id+", workloadUrlOption: "+workloadUrlOption.getOrElse("")+", searchProps.workloadUrl: "+searchProps.workloadUrl+", stateOption: "+stateOption.getOrElse(""))
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
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]"   // only return nodes that have changed since this time, empty string returns all
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostNodeHealthResponse],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postPatternNodeHealth2 = (apiOperation[PostNodeHealthResponse]("postPatternNodeHealth2") summary("a") description("a"))

  /** Called by the agbot to get recent info about nodes with this pattern (and the agreements the node has). */
  post("/orgs/:orgid/patterns/:pattern/nodehealth", operation(postPatternNodeHealth)) ({
    val orgid = swaggerHack("orgid")
    val pattern = params("pattern")   // but do not have a hack/fix for the name
    val compositePat = OrgAndId(orgid,pattern).toString
    credsAndLog().authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostNodeHealthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth criteria: "+searchProps.toString)
    val resp = response
    /*
      Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
      The filter is: n.pattern==ourpattern && n.lastHeartbeat>=lastTime
      Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
    */
    val lastTime = if (searchProps.lastTime != "") searchProps.lastTime else ApiTime.beginningUTC
    val q = for {
      (n, a) <- NodesTQ.rows.filter(_.orgid === orgid).filter(_.pattern === compositePat).filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
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

  // ======== POST /orgs/{orgid}/search/nodes ========================
  val postSearchNodes =
    (apiOperation[PostSearchNodesResponse]("postSearchNodes")
      summary("Returns matching nodes")
      description """Based on the input selection criteria, returns the matching nodes (RPis) in the exchange DB. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "desiredMicroservices": [    // list of data microservices you are interested in
    {
      "url": "https://bluehorizon.network/microservices/rtlsdr",
      "properties": [    // list of properties to match specific nodes/microservices
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
  "propertiesToReturn": [    // ignored right now
    "string"
  ],
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostSearchNodesRequest],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postSearchNodes2 = (apiOperation[PostSearchNodesRequest]("postSearchNodes2") summary("a") description("a"))

  /** Normally called by the agbot to search for available nodes. */
  post("/orgs/:orgid/search/nodes", operation(postSearchNodes)) ({
    val orgid = swaggerHack("orgid")
    credsAndLog().authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostSearchNodesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    searchProps.validate()
    logger.debug("POST /orgs/"+orgid+"/search/nodes criteria: "+searchProps.desiredMicroservices.toString)
    val resp = response
    // Narrow down the db query results as much as possible with db selects, then searchProps.matchesDbResults will do the rest.
    var q = for {
    //TODO: use this commented out line for the special case of IBM agbots being able to search nodes from all orgs
    //((d, m), p) <- NodesTQ.rows joinLeft RegMicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
      ((d, m), p) <- NodesTQ.getNonPatternNodes(orgid).filter(_.publicKey =!= "") joinLeft RegMicroservicesTQ.rows on (_.id === _.nodeId) joinLeft PropsTQ.rows on (_._2.map(_.msId) === _.msId)
    } yield (d, m, p)
    // Also filter out nodes that are too stale (have not heartbeated recently)
    if (searchProps.secondsStale > 0) q = q.filter(_._1.lastHeartbeat >= ApiTime.pastUTC(searchProps.secondsStale))

    var agHash: AgreementsHash = null
    db.run(NodeAgreementsTQ.getAgreementsWithState.result.flatMap({ agList =>
      logger.debug("POST /orgs/"+orgid+"/search/nodes aglist result size: "+agList.size)
      logger.trace("POST /orgs/"+orgid+"/search/nodes aglist result: "+agList.toString)
      agHash = new AgreementsHash(agList)
      q.result      // queue up our node/ms/prop query next
    })).map({ list =>
      logger.debug("POST /orgs/"+orgid+"/search/nodes result size: "+list.size)
      // logger.trace("POST /orgs/"+orgid+"/search/nodes result: "+list.toString)
      // logger.trace("POST /orgs/"+orgid+"/search/nodes agHash: "+agHash.agHash.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.POST_OK)   //todo: this check only works if there are no nodes at all
      else resp.setStatus(HttpCode.NOT_FOUND)
      val nodes = NodesTQ.parseJoin(superUser = false, list)
      searchProps.matches(nodes, agHash)
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
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostNodeHealthResponse],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postSearchNodeHealth2 = (apiOperation[PostNodeHealthResponse]("postSearchNodeHealth2") summary("a") description("a"))

  /** Called by the agbot to get recent info about nodes with no pattern (and the agreements the node has). */
  post("/orgs/:orgid/search/nodehealth", operation(postSearchNodeHealth)) ({
    val orgid = swaggerHack("orgid")
    //val pattern = params("patid")   // but do not have a hack/fix for the name
    //val compositePat = OrgAndId(orgid,pattern).toString
    credsAndLog().authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostNodeHealthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
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
      description """Adds a new node (RPi) to the exchange DB, or updates an existing node, and returns the microservice templates for the microservices being registered. This must be called by the user to add a node, and then can be called by that user or node to update itself. The **request body** structure:

```
{
  "token": "abc",       // node token, set by user when adding this node.
  "name": "rpi3",         // node name that you pick
  "pattern": "myorg/mypattern",      // (optional) points to a pattern resource that defines what workloads should be deployed to this type of node
  "registeredMicroservices": [    // list of data microservices you want to make available
    {
      "url": "https://bluehorizon.network/documentation/sdr-node-api",
      "numAgreements": 1,       // for now always set this to 1
      "policy": "{...}"     // the microservice policy file content as a json string blob
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
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this node to be contacted by agbots, empty string to use the built-in Exchange msg service
  "softwareVersions": {"horizon": "1.2.3"},      // various software versions on the node
  "publicKey": "ABCDEF"      // used by agbots to encrypt msgs sent to this node using the built-in Exchange msg service
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutNodesRequest],
          Option[String]("Node object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putNodes2 = (apiOperation[PutNodesRequest]("putNodes2") summary("a") description("a"))  // for some bizarre reason, the PutNodesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PUT /node/{id}. Must be called by user to add node, normally called by node to update itself. */
  put("/orgs/:orgid/nodes/:id", operation(putNodes)) ({
    // consider writing a customer deserializer that will do error checking on the body, see: https://gist.github.com/fehguy/4191861#file-gistfile1-scala-L74
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    val ident = credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val node = try { parse(request.body).extract[PutNodesRequest] }
    catch {
      case e: Exception => /*if (e.getMessage.contains("No usable value for publicKey")) {    // the specific exception is MappingException
          // try parsing again with the old structure
          val nodeOld = try { parse(request.body).extract[PutNodesRequestOld] }
          catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
          nodeOld.toPutNodesRequest
        }
        else*/ halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e))
    }
    node.validate()
    // val owner = if (isAuthenticatedUser(creds)) creds.id else ""
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    //val microTmpls = node.getMicroTemplates      // do this before creating/updating the entry in db, in case it can not find the templates
    val resp = response
    val patValidateAction = if (node.pattern != "") PatternsTQ.getPattern(node.pattern).length.result else DBIO.successful(1)
    db.run(patValidateAction.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" pattern validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0) NodesTQ.getNumOwned(owner).result.asTry
          else DBIO.failed(new Throwable("the referenced pattern does not exist in the exchange")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" num owned: "+xs)
      xs match {
        case Success(numOwned) => val maxNodes = ExchConfig.getInt("api.limits.maxNodes")
          if (maxNodes == 0 || numOwned <= maxNodes || owner == "") {    // when owner=="" we know it is only an update, otherwise we are not sure, but if they are already over the limit, stop them anyway
            val action = if (owner == "") node.getDbUpdate(id, orgid, owner) else node.getDbUpsert(id, orgid, owner)
            action.transactionally.asTry
          }
          else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxNodes+ " nodes")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(_) => AuthCache.nodes.putBoth(Creds(id,node.token),owner)    // the token passed in to the cache should be the non-hashed one
          resp.setStatus(HttpCode.PUT_OK)
          //microTmpls
          ApiResponse(ApiResponseType.OK, "node added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "node '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "node '"+id+"' not inserted or updated: "+t.getMessage)
          }
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/nodes/{id} ===============================
  val patchNodes =
    (apiOperation[Map[String,String]]("patchNodes")
      summary "Updates 1 attribute of a node"
      description """Updates some attributes of a node (RPi) in the exchange DB. This can be called by the user or the node. The **request body** structure can include **1 of these attributes**:

```
{
  "token": "abc",       // node token, set by user when adding this node.
  "name": "rpi3",         // node name that you pick
  "pattern": "myorg/mypattern",      // (optional) points to a pattern resource that defines what workloads should be run on this type of node
  "msgEndPoint": "whisper-id",    // msg service endpoint id for this node to be contacted by agbots, empty string to use the built-in Exchange msg service
  "softwareVersions": {"horizon": "1.2.3"},      // various software versions on the node
  "publicKey": "ABCDEF"      // used by agbots to encrypt msgs sent to this node using the built-in Exchange msg service
}
```

- **Due to a swagger bug, the format shown below is incorrect. Run the PATCH method to see the response format instead.**"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchNodesRequest],
          Option[String]("Node object that contains attributes to updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchNodes2 = (apiOperation[PatchNodesRequest]("patchNodes2") summary("a") description("a"))  // for some bizarre reason, the PatchNodesRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  /** Handles PATCH /node/{id}. Must be called by user to add node, normally called by node to update itself. */
  patch("/orgs/:orgid/nodes/:id", operation(patchNodes)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val node = try { parse(request.body).extract[PatchNodesRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/nodes/"+bareId+" input: "+node.toString)
    val resp = response
    val (action, attrName) = node.getDbUpdate(id)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid node attribute specified"))
    val patValidateAction = if (attrName == "pattern" && node.pattern.get != "") PatternsTQ.getPattern(node.pattern.get).length.result else DBIO.successful(1)
    db.run(patValidateAction.asTry.flatMap({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/nodes/"+bareId+" pattern validation: "+xs.toString)
      xs match {
        case Success(num) => if (num > 0) action.transactionally.asTry
          else DBIO.failed(new Throwable("the referenced pattern does not exist in the exchange")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              node.token match { case Some(tok) if (tok != "") => AuthCache.nodes.put(Creds(id, tok)); case _ => ; }    // the token passed in to the cache should be the non-hashed one. We do not need to run putOwner because patch does not change the owner
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of node '"+id+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "node '"+id+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "node '"+id+"' not inserted or updated: "+t.getMessage)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id} ===============================
  val deleteNodes =
    (apiOperation[ApiResponse]("deleteNodes")
      summary "Deletes a node"
      description "Deletes a node (RPi) from the exchange DB, and deletes the agreements stored for this node (but does not actually cancel the agreements between the node and agbots). Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/nodes/:id", operation(deleteNodes)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(NodesTQ.getNode(id).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.nodes.removeBoth(id)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "node deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "node '"+id+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "node '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== POST /orgs/{orgid}/nodes/{id}/heartbeat ===============================
  val postNodesHeartbeat =
    (apiOperation[ApiResponse]("postNodesHeartbeat")
      summary "Tells the exchange this node is still operating"
      description "Lets the exchange know this node is still active so it is still a candidate for contracting. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to be updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  post("/orgs/:orgid/nodes/:id/heartbeat", operation(postNodesHeartbeat)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodesTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/heartbeat result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {       // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.POST_OK)
              ApiResponse(ApiResponseType.OK, "node updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "node '"+id+"' not found")
            }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "node '"+id+"' not updated: "+t.toString)
        }
    })
  })


  /* ====== GET /orgs/{orgid}/nodes/{id}/status ================================ */
  val getNodeStatus =
    (apiOperation[NodeStatus]("getNodeStatus")
      summary("Returns the node status")
      description("""Returns the node run time status, for example workload container status. Can be run by a user or the node.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/nodes/:id/status", operation(getNodeStatus)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
      db.run(NodeStatusTQ.getNodeStatus(id).result).map({ list =>
        logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/status result size: "+list.size)
        if (list.nonEmpty) list.head.toNodeStatus
        else resp.setStatus(HttpCode.NOT_FOUND)
      })
  })

  // =========== PUT /orgs/{orgid}/nodes/{id}/status ===============================
  val putNodeStatus =
    (apiOperation[ApiResponse]("putNodeStatus")
      summary "Adds/updates the node status"
      description """Adds or updates the run time status of a node. This is called by the
        node or owning user. The **request body** structure:

```
{
  "connectivity": {
     "firmware.bluehorizon.network": true,
      "images.bluehorizon.network": true
   },
  "microservices": [
    {
      "specRef": "https://bluehorizon.network/microservices/gps",
      "orgid": "mycompany",
      "version": "2.0.4",
      "arch": "amd64",
      "contanerStatus": [
        {
            "name": "/bluehorizon.network-microservices-gps_2.0.4_78a98f1f-2eed-467c-aea2-278fb8161595-gps",
            "image": "summit.hovitos.engineering/x86/gps:2.0.4",
            "created": 1505939808,
            "state": "running"
        }
      ]
    }
  ],
  "workloads": [
    {
      "agreementId": "78d7912aafb6c11b7a776f77d958519a6dc718b9bd3da36a1442ebb18fe9da30",
      "workloadUrl":"https://bluehorizon.network/workloads/location",
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
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node wanting to add/update this status."), paramType = ParamType.Query),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PutNodeStatusRequest],
        Option[String]("Status object add or update. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val putNodeStatus2 = (apiOperation[PutNodeStatusRequest]("putNodeStatus2") summary("a") description("a"))  // for some bizarre reason, the PutNodeStatusRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id/status", operation(putNodeStatus)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val status = try { parse(request.body).extract[PutNodeStatusRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    db.run(status.toNodeStatusRow(id).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/status result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "status added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "status for node '"+id+"' not inserted or updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "status for node '"+id+"' not inserted or updated: "+t.toString)
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
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node for which the status is to be deleted."), paramType = ParamType.Path),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      )

  delete("/orgs/:orgid/nodes/:id/status", operation(deleteNodeStatus)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeStatusTQ.getNodeStatus(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/status result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "node status deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "status for node '"+id+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "status for node '"+id+"' not deleted: "+t.toString)
      }
    })
  })


  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements ================================ */
  val getNodeAgreements =
    (apiOperation[GetNodeAgreementsResponse]("getNodeAgreements")
      summary("Returns all agreements this node is in")
      description("""Returns all agreements in the exchange DB that this node is part of. Can be run by a user or the node.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node."), paramType=ParamType.Query),
      Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      )

  get("/orgs/:orgid/nodes/:id/agreements", operation(getNodeAgreements)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreements(id).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/agreements result size: "+list.size)
      val agreements = new MutableHashMap[String, NodeAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agId, e.toNodeAgreement) }
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetNodeAgreementsResponse(agreements.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/nodes/{id}/agreements/{agid} ================================ */
  val getOneNodeAgreement =
    (apiOperation[GetNodeAgreementsResponse]("getOneNodeAgreement")
      summary("Returns an agreement for a node")
      description("""Returns the agreement with the specified agid for the specified node id in the exchange DB. Can be run by a user or the node. **Because of a swagger bug this method can not be run via swagger.**

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node."), paramType=ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/nodes/:id/agreements/:agid", operation(getOneNodeAgreement)) ({
    val orgid = params("orgid")
    val bareId = swaggerHack("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    val agId = swaggerHack("agid")
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.READ)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreement(id, agId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+list.toString)
      val agreements = new MutableHashMap[String, NodeAgreement]
      if (list.nonEmpty) for (e <- list) { agreements.put(e.agId, e.toNodeAgreement) }
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
  "microservices": [          // specify this for CS-type agreements
    {"orgid": "myorg", "url": "https://bluehorizon.network/microservices/rtlsdr"}    // url is API spec ref
  ],
  "workload": {          // specify this for pattern-type agreements
    "orgid": "myorg",
    "pattern": "mynodetype",
    "url": "https://bluehorizon.network/workloads/sdr"
  },
  "state": "negotiating"    // current agreement state: negotiating, signed, finalized, etc.
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node wanting to add/update this agreement."), paramType = ParamType.Query),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be added/updated."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutNodeAgreementRequest],
          Option[String]("Agreement object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val putNodeAgreement2 = (apiOperation[PutNodeAgreementRequest]("putAgreement2") summary("a") description("a"))  // for some bizarre reason, the PutAgreementsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/nodes/:id/agreements/:agid", operation(putNodeAgreement)) ({
    //todo: keep a running total of agreements for each MS so we can search quickly for available MSs
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    val agId = params("agid")
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val agreement = try { if (""""microservice" *:""".r.findFirstIn(request.body).isDefined) throw new Exception("Instead of old attribute 'microservice', use list attribute 'microservices'") else parse(request.body).extract[PutNodeAgreementRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    val resp = response
    db.run(NodeAgreementsTQ.getNumOwned(id).result.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" num owned: "+xs)
      val numOwned = xs
      val maxAgreements = ExchConfig.getInt("api.limits.maxAgreements")
      if (maxAgreements == 0 || numOwned <= maxAgreements) {    // we are not sure if this is create or update, but if they are already over the limit, stop them anyway
        agreement.toNodeAgreementRow(id, agId).upsert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxAgreements+ " agreements for this node")).asTry
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "agreement added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "agreement '"+agId+"' for node '"+id+"' not inserted or updated: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agId+"' for node '"+id+"' not inserted or updated: "+t.toString)
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
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/nodes/:id/agreements", operation(deleteNodeAllAgreement)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreements(id).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/agreements result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "node agreements deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "no agreements for node '"+id+"' found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreements for node '"+id+"' not deleted: "+t.toString)
        }
    })
  })

  // =========== DELETE /orgs/{orgid}/nodes/{id}/agreements/{agid} ===============================
  val deleteNodeAgreement =
    (apiOperation[ApiResponse]("deleteNodeAgreement")
      summary "Deletes an agreement of a node"
      description "Deletes an agreement of a node from the exchange DB. Can be run by the owning user or the node."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node for which the agreement is to be deleted."), paramType = ParamType.Path),
        Parameter("agid", DataType.String, Option[String]("ID of the agreement to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/nodes/:id/agreements/:agid", operation(deleteNodeAgreement)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    val agId = params("agid")
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeAgreementsTQ.getAgreement(id,agId).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/agreements/"+agId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it  or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "node agreement deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "agreement '"+agId+"' for node '"+id+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "agreement '"+agId+"' for node '"+id+"' not deleted: "+t.toString)
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
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to send a msg to."), paramType = ParamType.Path),
        // Agbot id/token must be in the header
        Parameter("body", DataType[PostNodesMsgsRequest],
          Option[String]("Signed/encrypted message to send to the node. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val postNodesMsgs2 = (apiOperation[PostNodesMsgsRequest]("postNodesMsgs2") summary("a") description("a"))

  post("/orgs/:orgid/nodes/:id/msgs", operation(postNodesMsgs)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val nodeId = OrgAndId(orgid,bareId).toString
    val ident = credsAndLog().authenticate().authorizeTo(TNode(nodeId),Access.SEND_MSG_TO_NODE)
    val agbotId = ident.creds.id
    val msg = try { parse(request.body).extract[PostNodesMsgsRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
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
      else DBIO.failed(new Throwable("Access Denied: the message mailbox of "+nodeId+" is full ("+maxMessagesInMailbox+ " messages)")).asTry
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs agbot publickey result: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {    // it seems this returns success even when the agbot is not found
            val agbotPubKey = v.head
            if (agbotPubKey != "") NodeMsgRow(0, nodeId, agbotId, agbotPubKey, msg.message, ApiTime.nowUTC, ApiTime.futureUTC(msg.ttl)).insert.asTry
            else DBIO.failed(new Throwable("Invalid Input: the message sender must have their public key registered with the Exchange")).asTry
          }
          else DBIO.failed(new Throwable("Invalid Input: agbot "+agbotId+" not found")).asTry
        case Failure(t) => DBIO.failed(t).asTry       // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/nodes/"+bareId+"/msgs write row result: "+xs.toString)
      xs match {
        case Success(v) => resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "node msg "+v+" inserted")
        case Failure(t) => if (t.getMessage.startsWith("Invalid Input:")) {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "node '"+nodeId+"' msg not inserted: "+t.getMessage)
          } else if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "node '"+nodeId+"' msg not inserted: "+t.getMessage)
          } else {
            resp.setStatus(HttpCode.INTERNAL_ERROR)
            ApiResponse(ApiResponseType.INTERNAL_ERROR, "node '"+nodeId+"' msg not inserted: "+t.toString)
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
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node."), paramType=ParamType.Query),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/nodes/:id/msgs", operation(getNodeMsgs)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.READ)
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
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("ID (orgid/nodeid) of the node to be deleted."), paramType = ParamType.Path),
        Parameter("msgid", DataType.String, Option[String]("ID of the msg to be deleted."), paramType = ParamType.Path),
        Parameter("token", DataType.String, Option[String]("Token of the node. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/nodes/:id/msgs/:msgid", operation(deleteNodeMsg)) ({
    val orgid = swaggerHack("orgid")
    val bareId = params("id")   // but do not have a hack/fix for the name
    val id = OrgAndId(orgid,bareId).toString
    val msgId = try { params("msgid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "msgid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    credsAndLog().authenticate().authorizeTo(TNode(id),Access.WRITE)
    val resp = response
    db.run(NodeMsgsTQ.getMsg(id,msgId).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/nodes/"+bareId+"/msgs/"+msgId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "node msg deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "msg '"+msgId+"' for node '"+id+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "msg '"+msgId+"' for node '"+id+"' not deleted: "+t.toString)
        }
    })
  })

}
