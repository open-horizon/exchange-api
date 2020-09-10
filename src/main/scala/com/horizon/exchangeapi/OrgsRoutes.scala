/** Services routes for all of the /orgs api methods. */
package com.horizon.exchangeapi

import java.time.ZonedDateTime

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth.DBProcessingError

import scala.concurrent.ExecutionContext

// Not using the built-in spray json
//import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
//import spray.json.DefaultJsonProtocol
//import spray.json._

import de.heikoseeberger.akkahttpjackson._
import org.json4s._
import org.json4s.jackson.Serialization.write

import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.util._

/*someday: when we start using actors:
import akka.actor.{ ActorRef, ActorSystem }
import scala.concurrent.duration._
import com.horizon.exchangeapi.OrgsActor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.ExecutionContext
*/

// Note: These are the input and output structures for /orgs routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs */
final case class GetOrgsResponse(orgs: Map[String, Org], lastIndex: Int)
final case class GetOrgAttributeResponse(attribute: String, value: String)

/** Input format for PUT /orgs/<org-id> */
final case class PostPutOrgRequest(orgType: Option[String], label: String, description: String, tags: Option[Map[String, String]], limits: Option[OrgLimits], heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  require(label!=null && description!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(orgMaxNodes: Int): Option[String] = {
    val exchangeMaxNodes = ExchConfig.getInt("api.limits.maxNodes")
    if (orgMaxNodes > exchangeMaxNodes) Some(ExchMsg.translate("org.limits.cannot.be.over.exchange.limits", orgMaxNodes, exchangeMaxNodes))
    else None
  }

  def toOrgRow(orgId: String) = OrgRow(orgId, orgType.getOrElse(""), label, description, ApiTime.nowUTC, tags.map(ts => ApiUtils.asJValue(ts)), write(limits), write(heartbeatIntervals))
}

final case class PatchOrgRequest(orgType: Option[String], label: Option[String], description: Option[String], tags: Option[Map[String, Option[String]]], limits: Option[OrgLimits], heartbeatIntervals: Option[NodeHeartbeatIntervals]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem(orgMaxNodes: Int): Option[String] = {
    val exchangeMaxNodes = ExchConfig.getInt("api.limits.maxNodes")
    if (orgMaxNodes > exchangeMaxNodes) Some(ExchMsg.translate("org.limits.cannot.be.over.exchange.limits", orgMaxNodes, exchangeMaxNodes))
    else None
  }
  /** Returns a tuple of the db action to update parts of the org, and the attribute name being updated. */
  def getDbUpdate(orgId: String)(implicit executionContext: ExecutionContext): (DBIO[_], String) = {
    import com.horizon.exchangeapi.tables.ExchangePostgresProfile.plainAPI._
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this org
    orgType match { case Some(ot) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.orgType, d.lastUpdated)).update((orgId, ot, lastUpdated)), "orgType"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.label, d.lastUpdated)).update((orgId, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.description, d.lastUpdated)).update((orgId, desc, lastUpdated)), "description"); case _ => ; }
    heartbeatIntervals match { case Some(hbIntervals) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.heartbeatIntervals, d.lastUpdated)).update((orgId, write(hbIntervals), lastUpdated)), "heartbeatIntervals"); case _ => ; }
    tags match { case Some(ts) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.tags, d.lastUpdated)).update((orgId, Some(ApiUtils.asJValue(ts)), lastUpdated)), "tags"); case _ => ; }
//    tags match {
//      case Some(ts) =>
//        val (deletes, updates) = ts.partition {
//          case (_, v) => v.isEmpty
//        }
//        val dbUpdates =
//          if (updates.isEmpty) Seq()
//          else Seq(sqlu"update orgs set tags = coalesce(tags, '{}'::jsonb) || ${ApiUtils.asJValue(updates)} where orgid = $orgId")
//
//        val dbDeletes =
//          for (tag <- deletes.keys.toSeq) yield {
//            sqlu"update orgs set tags = tags - $tag where orgid = $orgId"
//          }
//        val allChanges = dbUpdates ++ dbDeletes
//        return (DBIO.sequence(allChanges).map(counts => counts.sum), "tags")
//      case _ =>
//    }
    limits match { case Some(lim) => return ((for { d <- OrgsTQ.rows if d.orgid === orgId } yield (d.orgid, d.limits, d.lastUpdated)).update((orgId, write(lim), lastUpdated)), "limits"); case _ => ; }
    return (null, null)
  }
}

/** The following classes are to build the response object for the GET /orgs/{orgid}/searc/nodes/error/all route */
final case class NodeErrorsResp(nodeId: String, error: String, lastUpdated: String)
final case class AllNodeErrorsInOrgResp(nodeErrors: ListBuffer[NodeErrorsResp])

/** Input body for POST /org/{orgid}/search/nodehealth */
final case class PostNodeHealthRequest(lastTime: String, nodeOrgids: Option[List[String]]) {
  require(lastTime!=null)
  def getAnyProblem: Option[String] = None
}

final case class NodeHealthAgreementElement(lastUpdated: String)
class NodeHealthHashElement(var lastHeartbeat: Option[String], var agreements: Map[String, NodeHealthAgreementElement])
final case class PostNodeHealthResponse(nodes: Map[String, NodeHealthHashElement])

/** Case class for request body for ResourceChanges route */
final case class ResourceChangesRequest(changeId: Long, lastUpdated: Option[String], maxRecords: Int, orgList: Option[List[String]]) {
  def getAnyProblem: Option[String] = None // None means no problems with input
}

/** The following classes are to build the response object for the ResourceChanges route */
final case class ResourceChangesInnerObject(changeId: Long, lastUpdated: String)
final case class ChangeEntry(orgId: String, var resource: String, id: String, var operation: String, resourceChanges: ListBuffer[ResourceChangesInnerObject]){
  def addToResourceChanges(innerObject: ResourceChangesInnerObject): ListBuffer[ResourceChangesInnerObject] = { this.resourceChanges += innerObject}
  def setOperation(newOp: String) {this.operation = newOp}
  def setResource(newResource: String) {this.resource = newResource}
}
final case class ResourceChangesRespObject(changes: List[ChangeEntry], mostRecentChangeId: Long, hitMaxRecords: Boolean, exchangeVersion: String)

final case class MaxChangeIdResponse(maxChangeId: Long)

/** Routes for /orgs */
@Path("/v1")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait OrgsRoutes extends JacksonSupport with AuthenticationSupport {
  // Not using Spray, but left here for reference, in case we want to switch to it - Tell spray how to marshal our types (models) to/from the rest client
  //import DefaultJsonProtocol._
  // Note: it is important to use the immutable version of collections like Map
  // Note: if you accidentally omit a class here, you may get a msg like: [error] /Users/bp/src/github.com/open-horizon/exchange-api/src/main/scala/com/horizon/exchangeapi/OrgsRoutes.scala:49:44: could not find implicit value for evidence parameter of type spray.json.DefaultJsonProtocol.JF[scala.collection.immutable.Seq[com.horizon.exchangeapi.TmpOrg]]
  /* implicit val apiResponseJsonFormat = jsonFormat2(ApiResponse)
  implicit val orgJsonFormat = jsonFormat5(Org)
  implicit val getOrgsResponseJsonFormat = jsonFormat2(GetOrgsResponse)
  implicit val getOrgAttributeResponseJsonFormat = jsonFormat2(GetOrgAttributeResponse)
  implicit val postPutOrgRequestJsonFormat = jsonFormat4(PostPutOrgRequest) */
  //implicit val actionPerformedJsonFormat = jsonFormat1(ActionPerformed)

  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  /* when using actors
  implicit def system: ActorSystem
  implicit val executionContext: ExecutionContext = context.system.dispatcher
  val orgsActor: ActorRef = system.actorOf(OrgsActor.props, "orgsActor") // I think this will end up instantiating OrgsActor via the creator function that is part of props
  logger.debug("OrgsActor created")
  // Required by the `ask` (?) method below
  implicit lazy val timeout = Timeout(5.seconds) //note: get this from the system's configuration
  */

  // Note: to make swagger work, each route should be returned by its own method: https://github.com/swagger-akka-http/swagger-akka-http
  def orgsRoutes: Route = orgsGetRoute ~ orgGetRoute ~ orgPostRoute ~ orgPutRoute ~ orgPatchRoute ~ orgDeleteRoute ~ orgPostNodesErrorRoute ~ nodeGetAllErrorsRoute ~ orgPostNodesServiceRoute ~ orgPostNodesHealthRoute ~ orgChangesRoute ~ orgsGetMaxChangeIdRoute

  // ====== GET /orgs ================================

  /* Akka-http Directives Notes:
  * Directives reference: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/alphabetically.html
  * The path() directive gobbles up the rest of the url path (until the params at ?). So you can't have any other path directives after it (and path directives before it must be pathPrefix())
  * Get variable parts of the route: path("orgs" / Segment) { orgid=>
  * Get the request context: get { ctx => println(ctx.request.method.name)
  * Get the request: extractRequest { request => println(request.headers.toString())
  * Concatenate directive extractions: (path("order" / IntNumber) & get & extractMethod) { (id, m) =>
  * For url query parameters, the single quote in scala means it is a symbol, the question mark means it's optional */

  // Swagger annotation reference: https://github.com/swagger-api/swagger-core/wiki/Swagger-2.X---Annotations
  // Note: i think these annotations can't have any comments between them and the method def
  @GET
  @Path("orgs")
  @Operation(summary = "Returns all orgs", description = "Returns some or all org definitions. Can be run by any user if filter orgType=IBM is used, otherwise can only be run by the root user.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM"))))),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this label (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "orgs": {
    "string" : {
      "orgType": "",
      "label": "",
      "description": "",
      "lastUpdated": "",
      "tags": null,
      "limits": {
        "maxNodes": 0
      },
      "heartbeatIntervals": {
        "minInterval": 0,
        "maxInterval": 0,
        "intervalAdjustment": 0
      }
    }.
      ...
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetOrgsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgsGetRoute: Route = (path("orgs") & get & parameter(('orgtype.?, 'label.?))) { (orgType, label) =>
    logger.debug(s"Doing GET /orgs with orgType:$orgType, label:$label")
    // If filter is orgType=IBM then it is a different access required than reading all orgs
    val access = if (orgType.getOrElse("").contains("IBM")) Access.READ_IBM_ORGS else Access.READ_OTHER_ORGS
    exchAuth(TOrg("*"), access) { ident =>
      validate(orgType.isEmpty || orgType.get == "IBM", ExchMsg.translate("org.get.orgtype")) {
        complete({ // this is an anonymous function that returns Future[(StatusCode, GetOrgsResponse)]
          logger.debug("GET /orgs identity: " + ident)
          var q = OrgsTQ.rows.subquery
          // If multiple filters are specified they are ANDed together by adding the next filter to the previous filter by using q.filter
          orgType match {
            case Some(oType) => if (oType.contains("%")) q = q.filter(_.orgType like oType) else q = q.filter(_.orgType === oType);
            case _ => ;
          }
          label match {
            case Some(lab) => if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab);
            case _ => ;
          }

          db.run(q.result).map({ list =>
            logger.debug("GET /orgs result size: " + list.size)
            val orgs = list.map(a => a.orgId -> a.toOrg).toMap
            val code = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetOrgsResponse(orgs, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // ====== GET /orgs/{orgid} ================================
  @GET
  @Path("orgs/{orgid}")
  @Operation(summary = "Returns an org", description = "Returns the org with the specified id. Can be run by any user in this org.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire org resource will be returned.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "orgs": {
    "string" : {
      "orgType": "",
      "label": "Test Org",
      "description": "No",
      "lastUpdated": "2020-08-25T14:04:21.707Z[UTC]",
      "tags": {
        "ibmcloud_id": ""
      },
      "limits": {
        "maxNodes": 0
      },
      "heartbeatIntervals": {
        "minInterval": 0,
        "maxInterval": 0,
        "intervalAdjustment": 0
      }
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetOrgsResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgGetRoute: Route = (path("orgs" / Segment) & get & parameter('attribute.?)) { (orgId, attribute) =>
    exchAuth(TOrg(orgId), Access.READ) { ident =>
      logger.debug(s"GET /orgs/$orgId ident: ${ident.getIdentity}")
      complete({
        attribute match {
          case Some(attr) => // Only returning 1 attr of the org
            val q = OrgsTQ.getAttribute(orgId, attr) // get the proper db query for this attribute
            if (q == null) (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.attr.not.part.of.org", attr)))
            else db.run(q.result).map({ list =>
              logger.debug(s"GET /orgs/$orgId attribute result: ${list.toString}")
              val code = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              // Note: scala is unhappy when db.run returns 2 different possible types, so we can't return ApiResponse in the case of not found
              if (list.nonEmpty) (code, GetOrgAttributeResponse(attr, OrgsTQ.renderAttribute(list)))
              else (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
            })

          case None => // Return the whole org resource
            db.run(OrgsTQ.getOrgid(orgId).result).map({ list =>
              logger.debug(s"GET /orgs/$orgId result size: ${list.size}")
              val orgs = list.map(a => a.orgId -> a.toOrg).toMap
              val code = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetOrgsResponse(orgs, 0))
            })
        } // attribute match
      }) // end of complete
    }   // end of exchAuth
  }

  // ====== POST /orgs/{orgid} ================================
  @POST
  @Path("orgs/{orgid}")
  @Operation(
    summary = "Adds an org",
    description = "Creates an org resource. This can only be called by the root user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "orgType": "my org type",   // (optional)
  "label": "My org",
  "description": "blah blah",
  "tags": {   // (optional)
    "ibmcloud_id": "abc123def456"
  },
  "limits": { // optional
    "maxNodes": 50
  },
  "heartbeatIntervals": {     // default values (in seconds) if not set in the node resource. This section can be omitted
    "minInterval": 10,        // the initial heartbeat interval
    "maxInterval": 120,       // the max the interval will ever become
    "intervalAdjustment": 10  // how much to increase the interval if there has been no activity for a while
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutOrgRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))
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
  def orgPostRoute: Route = (path("orgs" / Segment) & post & entity(as[PostPutOrgRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgId")
    exchAuth(TOrg(""), Access.CREATE) { _ =>
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          db.run(reqBody.toOrgRow(orgId).insert.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"POST /orgs/$orgId result: $n")
              val orgChange = ResourceChangeRow(0L, orgId, orgId, "org", "false", "org", ResourceChangeConfig.CREATED, ApiTime.nowUTCTimestamp)
              orgChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"POST /orgs/$orgId put in changes table: $n")
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.created", orgId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.created", orgId, t.getMessage)))
              else if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS2, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("org.already.exists", orgId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.created", orgId, t.toString))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("org.not.created", orgId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.created", orgId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // ====== PUT /orgs/{orgid} ================================
  @PUT
  @Path("orgs/{orgid}")
  @Operation(summary = "Updates an org", description = "Does a full replace of an existing org. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    requestBody = new RequestBody(description = "Does a full replace of an existing org.", required = true, content = Array(
      new Content(
      examples = Array(
        new ExampleObject(
          value = """{
  "orgType": "my org type",   // (optional)
  "label": "My org",
  "description": "blah blah",
  "tags": {   // (optional)
    "ibmcloud_id": "abc123def456"
  },
  "limits": { // optional
    "maxNodes": 50
  },
  "heartbeatIntervals": {     // default values (in seconds) if not set in the node resource. This section can be omitted
    "minInterval": 10,        // the initial heartbeat interval
    "maxInterval": 120,       // the max the interval will ever become
    "intervalAdjustment": 10  // how much to increase the interval if there has been no activity for a while
  }
}
"""
        )
      ),
      mediaType = "application/json",
      schema = new Schema(implementation = classOf[PostPutOrgRequest])
    ))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPutRoute: Route = (path("orgs" / Segment) & put & entity(as[PostPutOrgRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgId with orgId:$orgId")
    val access = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
    exchAuth(TOrg(orgId), access) { _ =>
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          db.run(reqBody.toOrgRow(orgId).update.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PUT /orgs/$orgId result: $n")
              if (n.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                val orgChange = ResourceChangeRow(0L, orgId, orgId, "org", "false", "org", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTCTimestamp)
                orgChange.insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"PUT /orgs/$orgId updated in changes table: $n")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", orgId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // ====== PATCH /orgs/{orgid} ================================
  @PATCH
  @Path("orgs/{orgid}")
  @Operation(summary = "Updates 1 attribute of an org", description = "Updates one attribute of a org. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes:", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "ibmcloud_id": "abc123def456"
  },
  "limits": {
    "maxNodes": 0
  },
  "heartbeatIntervals": {     // default values (in seconds) if not set in the node resource. This section can be omitted
    "minInterval": 10,        // the initial heartbeat interval
    "maxInterval": 120,       // the max the interval will ever become
    "intervalAdjustment": 10  // how much to increase the interval if there has been no activity for a while
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutOrgRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPatchRoute: Route = (path("orgs" / Segment) & patch & entity(as[PatchOrgRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgId with orgId:$orgId")
    val access = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
    exchAuth(TOrg(orgId), access) { _ =>
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(orgId)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.org.attr.specified")))
          else db.run(action.transactionally.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PATCH /orgs/$orgId result: $n")
              if (n.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                val orgChange = ResourceChangeRow(0L, orgId, orgId, "org", "false", "org", ResourceChangeConfig.MODIFIED, ApiTime.nowUTCTimestamp)
                orgChange.insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"PATCH /orgs/$orgId updated in changes table: $n")
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.attr.updated", attrName, orgId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.updated", orgId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.updated", orgId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }


  // =========== DELETE /orgs/{org} ===============================
  @DELETE
  @Path("orgs/{orgid}")
  @Operation(summary = "Deletes an org", description = "Deletes an org. This can only be called by root or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgDeleteRoute: Route = (path("orgs" / Segment) & delete) { (orgId) =>
    logger.debug(s"Doing DELETE /orgs/$orgId")
    exchAuth(TOrg(orgId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        db.run(OrgsTQ.getOrgid(orgId).delete.transactionally.asTry).map({
          case Success(v) => // there were no db errors, but determine if it actually found it or not
            logger.debug(s"DELETE /orgs/$orgId result: $v")
            if (v > 0) (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.deleted")))
            else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.deleted", orgId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.deleted", orgId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/search/nodes/error ========================
  @POST
  @Path("orgs/{orgid}/search/nodes/error")
  @Operation(summary = "Returns nodes in an error state", description = "Returns a list of the id's of nodes in an error state. Can be run by a user or agbot (but not a node). No request body is currently required.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[PostNodeErrorResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPostNodesErrorRoute: Route = (path("orgs" / Segment / "search" / "nodes" / "error") & post) { (orgid) =>
    logger.debug(s"Doing POST /orgs/$orgid/search/nodes/error")
    exchAuth(TNode(OrgAndId(orgid,"#").toString),Access.READ) { ident =>
      complete({
        var queryParam = NodesTQ.rows.filter(_.orgid === orgid)
        val userId = orgid + "/" + ident.getIdentity
        ident match {
          case _: IUser => if(!(ident.isSuperUser || ident.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
          case _ => ;
        }
        val q = for {
          (n, _) <- NodeErrorTQ.rows.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
        } yield n.nodeId

        db.run(q.result).map({ list =>
          logger.debug("POST /orgs/"+orgid+"/search/nodes/error result size: "+list.size)
          val code = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
          (code, PostNodeErrorResponse(list))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/search/nodes/error/all ================================ */
  @GET
  @Path("orgs/{orgid}/search/nodes/error/all")
  @Operation(summary = "Returns all node errors", description = "Returns a list of all the node errors for an organization (that the caller has access to see) in an error state. Can be run by a user or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[AllNodeErrorsInOrgResp])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetAllErrorsRoute: Route = (path("orgs" / Segment / "search"  / "nodes" / "error" / "all") & get) { orgid =>
    logger.debug(s"Doing GET /orgs/$orgid/search/nodes/error/all")
    exchAuth(TNode(OrgAndId(orgid,"#").toString),Access.READ) { ident =>
      complete({
        var queryParam = NodesTQ.rows.filter(_.orgid === orgid)
        val userId = orgid + "/" + ident.getIdentity
        ident match {
          case _: IUser => if(!(ident.isSuperUser || ident.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
          case _ => ;
        }
        val q = for {
          (ne, _) <- NodeErrorTQ.rows.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
        } yield (ne.nodeId, ne.errors, ne.lastUpdated)

        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/search/nodes/error/all result size: "+list.size)
          if (list.nonEmpty) {
            val errorsList = ListBuffer[NodeErrorsResp]()
            for ((nodeId, errorsString, lastUpdated) <- list) { errorsList += NodeErrorsResp(nodeId, errorsString, lastUpdated)}
            (HttpCode.OK, AllNodeErrorsInOrgResp(errorsList))
          }
          else {(HttpCode.OK, AllNodeErrorsInOrgResp(ListBuffer[NodeErrorsResp]()))}
        }) // end of db.run()
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/search/nodes/service  ===============================
  @POST
  @Path("orgs/{orgid}/search/nodes/service")
  @Operation(
    summary = "Returns the nodes a service is running on",
    description = "Returns a list of all the nodes a service is running on. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "orgid": "string",          // orgid of the service to be searched on
  "serviceURL": "string",
  "serviceVersion": "string",
  "serviceArch": "string"
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostServiceSearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body:",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """{
  "nodes": [
    {
      "string": "string",
      "string": "string"
    }
  ]
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[PostServiceSearchResponse])
          )
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
  def orgPostNodesServiceRoute: Route = (path("orgs" / Segment / "search" / "nodes" / "service") & post & entity(as[PostServiceSearchRequest])) { (orgid, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgid/search/nodes/service")
    exchAuth(TNode(OrgAndId(orgid,"#").toString),Access.READ) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val service = reqBody.serviceURL+"_"+reqBody.serviceVersion+"_"+reqBody.serviceArch
          logger.debug("POST /orgs/"+orgid+"/search/nodes/service criteria: "+reqBody.toString)
          val orgService = "%|"+reqBody.orgid+"/"+service+"|%"
          var qFilter = NodesTQ.rows.filter(_.orgid === orgid)
          ident match {
            case _: IUser =>
              // if the caller is a normal user then we need to only return node the caller owns
              if(!ident.isSuperUser || !ident.isAdmin) qFilter = qFilter.filter(_.owner === ident.identityString)
            case _ => ; // nodes can't call this route and agbots don't need an additional filter
          }
          val q = for {
            (n, _) <- qFilter join (NodeStatusTQ.rows.filter(_.runningServices like orgService)) on (_.id === _.nodeId)
          } yield (n.id, n.lastHeartbeat)

          db.run(q.result).map({ list =>
            logger.debug("POST /orgs/"+orgid+"/services/"+service+"/search result size: "+list.size)
            val code = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
            (code, PostServiceSearchResponse(list))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/search/nodehealth ========================
  @POST
  @Path("orgs/{orgid}/search/nodehealth")
  @Operation(
    summary = "Returns agreement health of nodes with no pattern",
    description = "Returns the lastHeartbeat and agreement times for all nodes in this org that do not have a pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]"  // only return nodes that have changed since this time, empty string returns all
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodeHealthRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body:",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "nodes": {
    "string": {
      "lastHeartbeat": "string",
      "agreements": {
        "string": {
          "lastUpdated": "string"
        },
          ...
      }
    },
      ...
  }
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[PostNodeHealthResponse])
          )
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
  def orgPostNodesHealthRoute: Route = (path("orgs" / Segment / "search" / "nodehealth") & post & entity(as[PostNodeHealthRequest])) { (orgid, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgid/search/nodehealth")
    exchAuth(TNode(OrgAndId(orgid,"*").toString),Access.READ) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          /*
            Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
            The filter is: n.pattern=="" && n.lastHeartbeat>=lastTime
            Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
          */
          val lastTime = if (reqBody.lastTime != "") reqBody.lastTime else ApiTime.beginningUTC
          val q = for {
            (n, a) <- NodesTQ.rows.filter(_.orgid === orgid).filter(_.pattern === "").filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
          } yield (n.id, n.lastHeartbeat, a.map(_.agId), a.map(_.lastUpdated))

          db.run(q.result).map({ list =>
            logger.debug("POST /orgs/"+orgid+"/search/nodehealth result size: "+list.size)
            //logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result: "+list.toString)
            if (list.nonEmpty) (HttpCode.POST_OK, PostNodeHealthResponse(RouteUtils.buildNodeHealthHash(list)))
            else (HttpCode.NOT_FOUND, PostNodeHealthResponse(Map[String,NodeHealthHashElement]()))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  def buildResourceChangesResponse(inputList: scala.Seq[ResourceChangeRow], hitMaxRecords: Boolean, inputChangeId: Long, maxChangeIdOfTable: Long): ResourceChangesRespObject ={
    // Sort the rows based on the changeId. Default order is ascending, which is what we want
    logger.info(s"POST /orgs/{orgid}/changes sorting ${inputList.size} rows")
    // val inputList = inputListUnsorted.sortBy(_.changeId)  // Note: we are doing the sorting here instead of in the db via sql, because the latter seems to use a lot of db cpu

    // fill in some values we can before processing
    val exchangeVersion = ExchangeApi.adminVersion()
    // set up needed variables
    val maxChangeIdInResponse = inputList.last.changeId
    val changesMap = scala.collection.mutable.Map[String, ChangeEntry]() //using a Map allows us to avoid having a loop in a loop when searching the map for the resource id
    // fill in changesMap
    for (entry <- inputList) { // looping through every single ResourceChangeRow in inputList, given that we apply `.take(maxRecords)` in the query, this should never be over maxRecords, so no more need to break
      val resChange = ResourceChangesInnerObject(entry.changeId, ApiTime.fixFormatting(entry.lastUpdated.toString))
      changesMap.get(entry.orgId+"_"+entry.id+"_"+entry.resource) match { // using the map allows for better searching and entry
        case Some(change) =>
          // inputList is already sorted by changeId from the query so we know this change happened later
          change.addToResourceChanges(resChange) // add the changeId and lastUpdated to the list of recent changes
          change.setOperation(entry.operation) // update the most recent operation performed
        case None => // add the change to the changesMap
          val resChangeListBuffer = ListBuffer[ResourceChangesInnerObject](resChange)
          changesMap.put(entry.orgId+"_"+entry.id+"_"+entry.resource, ChangeEntry(entry.orgId, entry.resource, entry.id, entry.operation, resChangeListBuffer))
      } // end of match
    } // end of for loop
    // now we have changesMap which is Map[String, ChangeEntry] we need to convert that to a List[ChangeEntry]
    val changesList = changesMap.values.toList
    var maxChangeId = 0L
    if (hitMaxRecords) maxChangeId = maxChangeIdInResponse   // we hit the max records, so there are possibly value entries we are not returning, so the client needs to start here next time
    else if (maxChangeIdOfTable > 0) maxChangeId = maxChangeIdOfTable   // we got a valid max change id in the table, and we returned all relevant entries, so the client can start at the end of the table next time
    else maxChangeId = inputChangeId    // we didn't get a valid maxChangeIdInResponse or maxChangeIdOfTable, so just give the client back what they gave us
    ResourceChangesRespObject(changesList, maxChangeId, hitMaxRecords, exchangeVersion)
  }

  /* ====== POST /orgs/{orgid}/changes ================================ */
  @POST
  @Path("orgs/{orgid}/changes")
  @Operation(
    summary = "Returns recent changes in this org",
    description = "Returns all the recent resource changes within an org that the caller has permissions to view.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "changeId": <number-here>,
  "lastUpdated": "<time-here>",  // (optional) only use if the caller doesn't know what changeId to use
  "maxRecords": <number-here>,   // (required) the maximum number of records the caller wants returned to them
  "orgList": ["", "", ""]        // (optional) just for agbots, this should be the list of orgs the agbot is responsible for
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[ResourceChangesRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "changes returned - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ResourceChangesRespObject])))
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
  def orgChangesRoute: Route = (path("orgs" / Segment / "changes") & post & entity(as[ResourceChangesRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing POST /orgs/$orgId/changes")
    exchAuth(TOrg(orgId), Access.READ) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          // make sure callers obey maxRecords cap set in config, defaults is 10,000
          val maxRecordsCap = ExchConfig.getInt("api.resourceChanges.maxRecordsCap")
          val maxRecords = if (reqBody.maxRecords > maxRecordsCap) maxRecordsCap else reqBody.maxRecords
          // Create a query to get the last changeid currently in the table
          val qMaxChangeId = ResourceChangesTQ.rows.sortBy(_.changeId.desc).take(1).map(_.changeId)
          val orgList : List[String] = if (reqBody.orgList.isDefined && reqBody.orgList.contains(orgId)) reqBody.orgList.getOrElse(List("")) else reqBody.orgList.getOrElse(List("")) ++ List(orgId)
          val orgSet : Set[String] = orgList.toSet
          var maxChangeId = 0L
          val reqBodyTime : java.sql.Timestamp = java.sql.Timestamp.from(ZonedDateTime.parse(reqBody.lastUpdated.getOrElse(ApiTime.beginningUTC)).toInstant)
          // Create query to get the rows relevant to this client. We only support either changeId or lastUpdated being specified, but not both
          var qFilter = if (reqBody.lastUpdated.getOrElse("") != "" && reqBody.changeId <= 0) ResourceChangesTQ.rows.filter(_.lastUpdated >= reqBodyTime) else ResourceChangesTQ.rows.filter(_.changeId >= reqBody.changeId)

          ident match {
            case _: INode =>
              // if its a node calling then it doesn't want information about any other nodes
              qFilter = qFilter.filter(u => (u.orgId === orgId) || (u.orgId =!= orgId && u.public === "true")).filter(u => (u.category === "node" && u.id === ident.getIdentity) || u.category =!= "node")
            case _: IAgbot =>
              val wildcard = orgSet.contains("*") || orgSet.contains("")
              if (ident.isMultiTenantAgbot && !wildcard) { // its an IBM Agbot with no wildcard sent in, get all changes from orgs the agbot covers
                qFilter = qFilter.filter(_.orgId inSet orgSet).filterNot(_.resource === "nodemsgs").filterNot(_.resource === "nodestatus").filterNot(u => u.resource === "nodeagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED).filterNot(u => u.resource === "agbotagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED)
              } else if ( ident.isMultiTenantAgbot && wildcard) {
                // if the IBM agbot sends in the wildcard case then we don't want to filter on orgId at all
                qFilter = qFilter.filterNot(_.resource === "nodemsgs").filterNot(_.resource === "nodestatus").filterNot(u => u.resource === "nodeagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED).filterNot(u => u.resource === "agbotagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED)
              } else {
                qFilter = qFilter.filter(u => (u.orgId === orgId) || (u.orgId =!= orgId && u.public === "true")).filterNot(_.resource === "nodemsgs").filterNot(_.resource === "nodestatus").filterNot(u => u.resource === "nodeagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED).filterNot(u => u.resource === "agbotagreements" && u.operation === ResourceChangeConfig.CREATEDMODIFIED) // if its not an IBM agbot only allow access to the agbot's own org and public changes from other orgs
              }
            case _ => qFilter = qFilter.filter(u => (u.orgId === orgId) || (u.orgId =!= orgId && u.public === "true"))
          }
          // sort by changeId and take only maxRecords from the query
          qFilter = qFilter.sortBy(_.changeId).take(maxRecords)

          logger.debug(s"POST /orgs/$orgId/changes db query: ${qFilter.result.statements}")
          var qResp : scala.Seq[ResourceChangeRow] = null

          db.run(qMaxChangeId.result.asTry.flatMap({
            case Success(qMaxChangeIdResp) =>
              maxChangeId = if (qMaxChangeIdResp.nonEmpty) qMaxChangeIdResp.head else 0
              qFilter.result.asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(qResult) =>
              //logger.debug("POST /orgs/" + orgId + "/changes changes : " + qOrgResult.toString())
              logger.debug("POST /orgs/" + orgId + "/changes number of changed rows retrieved: " + qResult.size)
              qResp = qResult
              val id = orgId + "/" + ident.getIdentity
              ident match {
                case _: INode =>
                  NodesTQ.getLastHeartbeat(id).update(Some(ApiTime.nowUTC)).asTry
                case _: IAgbot =>
                  AgbotsTQ.getLastHeartbeat(id).update(ApiTime.nowUTC).asTry
                case _ =>
                  // Caller isn't a node or agbot so no need to heartbeat, just send a success in this step
                  // v in the next step must be > 0 so any n > 0 works
                  DBIO.successful(1).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(n) =>
              logger.debug(s"POST /orgs/$orgId/changes node/agbot heartbeat result: $n")
              if (n > 0) {
                val hitMaxRecords = (qResp.size >= maxRecords) // if they are equal then we hit maxRecords
                if(qResp.nonEmpty) (HttpCode.POST_OK, buildResourceChangesResponse(qResp, hitMaxRecords, reqBody.changeId, maxChangeId))
                else (HttpCode.POST_OK, ResourceChangesRespObject(List[ChangeEntry](), maxChangeId, hitMaxRecords = false, ExchangeApi.adminVersion()))
              }
            else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("node.or.agbot.not.found", ident.getIdentity)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // ====== GET /changes/maxchangeid ================================
  @GET
  @Path("changes/maxchangeid")
  @Operation(summary = "Returns the max changeid of the resource changes", description = "Returns the max changeid of the resource changes. Can be run by any user, node, or agbot.",
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[MaxChangeIdResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied")))
  def orgsGetMaxChangeIdRoute: Route = (path("changes" / "maxchangeid") & get) {
    logger.debug("Doing GET /changes/maxchangeid")
    exchAuth(TAction(), Access.MAXCHANGEID) { _ =>
      complete({
        val q = ResourceChangesTQ.rows.sortBy(_.changeId.desc).take(1).map(_.changeId)
        logger.debug(s"GET /changes/maxchangeid db query: ${q.result.statements}")

        db.run(q.result).map({ changeIds =>
          logger.debug("GET /changes/maxchangeid result: " + changeIds)
          val changeId = if (changeIds.nonEmpty) changeIds.head else 0
          (StatusCodes.OK, MaxChangeIdResponse(changeId))
        })
      }) // end of complete
    } // end of exchAuth
  }

}
