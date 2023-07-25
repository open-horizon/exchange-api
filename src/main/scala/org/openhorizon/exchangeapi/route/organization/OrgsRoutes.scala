/** Services routes for all of the /orgs api methods. */
package org.openhorizon.exchangeapi.route.organization

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.checkerframework.checker.units.qual.t
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi
import org.openhorizon.exchangeapi.auth.{DBProcessingError, IamAccountInfo, IbmCloudAuth}
import org.openhorizon.exchangeapi.route.agreementbot.PostAgreementsConfirmRequest
import org.openhorizon.exchangeapi.route.node.{PostNodeErrorResponse, PostServiceSearchRequest, PostServiceSearchResponse}
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import org.openhorizon.exchangeapi.table.{organization, resourcechange, _}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.agreement.AgbotAgreementsTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.{NodeHeartbeatIntervals, NodesTQ}
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.node.status.NodeStatusTQ
import org.openhorizon.exchangeapi.table.organization.{Org, OrgLimits, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.schema.SchemaTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, ApiUtils, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangeApi, ExchangePosgtresErrorHandling, HttpCode, IAgbot, INode, IUser, OrgAndId, RouteUtils, TAction, TAgbot, TNode, TOrg, table}

import java.lang.IllegalCallerException
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.math.Ordered.orderingToOrdered
import scala.util._

/*someday: when we start using actors:
import akka.actor.{ ActorRef, ActorSystem }
import scala.concurrent.duration._
import org.openhorizon.exchangeapi.OrgsActor._
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.ExecutionContext
*/

// Note: These are the input and output structures for /orgs routes. Swagger and/or json seem to require they be outside the trait.

final case class GetMyOrgsRequest(accounts: List[IamAccountInfo]){
  def getAnyProblem: Option[String] = None
}

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
  def orgsRoutes: Route = orgsGetRoute ~
                          orgGetRoute ~
                          orgStatusRoute ~
                          orgPostRoute ~
                          orgPutRoute ~
                          orgPatchRoute ~
                          orgDeleteRoute ~
                          orgPostNodesErrorRoute ~
                          nodeGetAllErrorsRoute ~
                          orgPostNodesServiceRoute ~
                          orgPostNodesHealthRoute ~
                          myOrgsPostRoute ~
                          agbotAgreementConfirmRoute

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
  @Operation(summary = "Returns all orgs", description = "Returns some or all org definitions. Can be run by any user if filter orgType=IBM is used, otherwise can only be run by the root user or a hub admin.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. Currently the only supported org type for this route is 'IBM'.",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM"))))),
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
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgsGetRoute: Route = (path("orgs") & get & parameter("orgtype".?, "label".?)) { (orgType, label) =>
    logger.debug(s"Doing GET /orgs with orgType:$orgType, label:$label")
    // If filter is orgType=IBM then it is a different access required than reading all orgs
    val access: exchangeapi.Access.Value = if (orgType.getOrElse("").contains("IBM")) Access.READ_IBM_ORGS else Access.READ_OTHER_ORGS
    exchAuth(TOrg("*"), access) { ident =>
      validate(orgType.isEmpty || orgType.get == "IBM", ExchMsg.translate("org.get.orgtype")) {
        complete({ // this is an anonymous function that returns Future[(StatusCode, GetOrgsResponse)]
          logger.debug(s"GET /orgs identity: ${ident.creds.id}") // can't display the whole ident object, because that contains the pw/token
          var q = OrgsTQ.subquery
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
            val orgs: Map[String, Org] = list.map(a => a.orgId -> a.toOrg).toMap
            val code: StatusCode = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetOrgsResponse(orgs, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // ====== GET /orgs/{orgid} ================================
  @GET
  @Path("orgs/{orgid}")
  @Operation(summary = "Returns an org", description = "Returns the org with the specified id. Can be run by any user in this org or a hub admin.",
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
        "cloud_id": ""
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
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgGetRoute: Route = (path("orgs" / Segment) & get & parameter("attribute".?)) { (orgId, attribute) =>
    exchAuth(TOrg(orgId), Access.READ) { ident =>
      logger.debug(s"GET /orgs/$orgId ident: ${ident.getIdentity}")
      complete({
        attribute match {
          case Some(attr) => // Only returning 1 attr of the org
            val q: org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api.Query[_, _, Seq] = OrgsTQ.getAttribute(orgId, attr) // get the proper db query for this attribute
            if (q == null) (StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("org.attr.not.part.of.org", attr)))
            else db.run(q.result).map({ list =>
              logger.debug(s"GET /orgs/$orgId attribute result: ${list.toString}")
              val code: StatusCode = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              // Note: scala is unhappy when db.run returns 2 different possible types, so we can't return ApiResponse in the case of not found
              if (list.nonEmpty) (code, GetOrgAttributeResponse(attr, OrgsTQ.renderAttribute(list)))
              else (StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
            })

          case None => // Return the whole org resource
            db.run(OrgsTQ.getOrgid(orgId).result).map({ list =>
              logger.debug(s"GET /orgs/$orgId result size: ${list.size}")
              val orgs: Map[String, Org] = list.map(a => a.orgId -> a.toOrg).toMap
              val code: StatusCode = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetOrgsResponse(orgs, 0))
            })
        } // attribute match
      }) // end of complete
    }   // end of exchAuth
  }
  // ====== GET /orgs/{orgid}/status ================================
  @GET
  @Path("orgs/{orgid}/status")
  @Operation(summary = "Returns summary status of the org", description = "Returns the totals of key resources in the org. Can be run by any id in this org or a hub admin.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetOrgStatusResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgStatusRoute: Route = (path("orgs" / Segment /"status") & get ) { (orgId) =>
    exchAuth(TOrg(orgId), Access.READ) { ident =>
      logger.debug(s"GET /orgs/$orgId/status")
      complete({
        val statusResp = new OrgStatus()
        //perf: use a DBIO.sequence instead. It does essentially the same thing, but more efficiently
        db.run(UsersTQ.getAllUsers(orgId).length.result.asTry.flatMap({
          case Success(v) => statusResp.numberOfUsers = v
            NodesTQ.getAllNodes(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodes = v
            NodeAgreementsTQ.getAgreementsWithState(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeAgreements = v
            NodesTQ.getRegisteredNodesInOrg(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfRegisteredNodes = v
            NodeMsgsTQ.getNodeMsgsInOrg(orgId).length.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) => statusResp.numberOfNodeMsgs = v
            SchemaTQ.getSchemaVersion.result.asTry
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) => statusResp.dbSchemaVersion = v.head
            statusResp.msg = ExchMsg.translate("exchange.server.operating.normally")
            (HttpCode.OK, statusResp.toGetOrgStatusResponse)
          case Failure(t: org.postgresql.util.PSQLException) =>
            if (t.getMessage.contains("An I/O error occurred while sending to the backend")) (HttpCode.BAD_GW, statusResp.toGetOrgStatusResponse)
            else (HttpCode.INTERNAL_ERROR, statusResp.toGetOrgStatusResponse)
          case Failure(t) => statusResp.msg = t.getMessage
            (HttpCode.INTERNAL_ERROR, statusResp.toGetOrgStatusResponse)
        })
      }) // end of complete
    } // end of exchAuth
  }
  // ====== POST /orgs/{orgid} ================================
  @POST
  @Path("orgs/{orgid}")
  @Operation(
    summary = "Adds an org",
    description = "Creates an org resource. This can only be called by the root user or a hub admin.",
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
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "ibmcloud_id": "abc123def456",
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 50
  },
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
          schema = new Schema(implementation = classOf[PostPutOrgRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
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
        responseCode = "409",
        description = "conflict (org already exists)"
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
              ResourceChange(0L, orgId, orgId, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.CREATED).insert.asTry
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
  @Operation(summary = "Updates an org", description = "Does a full replace of an existing org. This can only be called by root, a hub admin, or a user in the org with the admin role.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    requestBody = new RequestBody(description = "Does a full replace of an existing org.", required = true, content = Array(
      new Content(
      examples = Array(
        new ExampleObject(
          value = """{
  "orgType": "my org type",
  "label": "My org",
  "description": "blah blah",
  "tags": {
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 50
  },
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
      schema = new Schema(implementation = classOf[PostPutOrgRequest])
    ))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPutRoute: Route = (path("orgs" / Segment) & put & entity(as[PostPutOrgRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing PUT /orgs/$orgId with orgId:$orgId")
    val access: exchangeapi.Access.Value = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
    exchAuth(TOrg(orgId), access) { _ =>
      validateWithMsg(reqBody.getAnyProblem(reqBody.limits.getOrElse(OrgLimits(0)).maxNodes)) {
        complete({
          db.run(reqBody.toOrgRow(orgId).update.asTry.flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug(s"PUT /orgs/$orgId result: $n")
              if (n.asInstanceOf[Int] > 0) { // there were no db errors, but determine if it actually found it or not
                ResourceChange(0L, orgId, orgId, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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
  @Operation(summary = "Updates 1 attribute of an org", description = "Updates one attribute of a org. This can only be called by root, a hub admin, or a user in the org with the admin role.",
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
    "cloud_id": "<account-id-here>"
  },
  "limits": {
    "maxNodes": 0
  },
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
        schema = new Schema(implementation = classOf[PostPutOrgRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPatchRoute: Route = (path("orgs" / Segment) & patch & entity(as[PatchOrgRequest])) { (orgId, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgId with orgId:$orgId")
    val access: exchangeapi.Access.Value = if (reqBody.orgType.getOrElse("") == "IBM") Access.SET_IBM_ORG_TYPE else Access.WRITE
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
                resourcechange.ResourceChange(0L, orgId, orgId, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.MODIFIED).insert.asTry
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
  @Operation(summary = "Deletes an org", description = "Deletes an org. This can only be called by root or a hub admin.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgDeleteRoute: Route = (path("orgs" / Segment) & delete) { (orgId) =>
    logger.debug(s"Doing DELETE /orgs/$orgId")
    exchAuth(TOrg(orgId), Access.DELETE_ORG) { _ =>
      validate(orgId != "root", ExchMsg.translate("cannot.delete.root.org")) {
        complete({
          // DB actions to get the user/agbot/node id's in this org
          var getResourceIds = DBIO.sequence(Seq(UsersTQ.getAllUsersUsername(orgId).result, AgbotsTQ.getAllAgbotsId(orgId).result, NodesTQ.getAllNodesId(orgId).result))
          var resourceIds: Seq[Seq[String]] = null
          var orgFound = true
          // remove does *not* throw an exception if the key does not exist
          db.run(getResourceIds.asTry.flatMap({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$orgId remove from cache: users: ${v(0).size}, agbots: ${v(1).size}, nodes: ${v(2).size}")
              resourceIds = v // save for a subsequent step - this is a vector of 3 vectors
              OrgsTQ.getOrgid(orgId).delete.transactionally.asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$orgId result: $v")
              if (v > 0) { // there were no db errors, but determine if it actually found it or not
                resourcechange.ResourceChange(0L, orgId, orgId, ResChangeCategory.ORG, false, ResChangeResource.ORG, ResChangeOperation.DELETED).insert.asTry
              } else {
                orgFound = false
                DBIO.successful("no update in resourcechanges table").asTry // just give a success to get us to the next step, but notify that it wasn't added to the resourcechanges table
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug(s"DELETE /orgs/$orgId updated in changes table: $v")
              if (orgFound && resourceIds!=null) {
                // Loop thru user/agbot/node id's and remove them from the cache
                for (id <- resourceIds(0)) { /*println(s"removing $id from cache");*/ AuthCache.removeUser(id) } // users
                for (id <- resourceIds(1)) { /*println(s"removing $id from cache");*/ AuthCache.removeAgbotAndOwner(id) } // agbots
                for (id <- resourceIds(2)) { /*println(s"removing $id from cache");*/ AuthCache.removeNodeAndOwner(id) } // nodes
                IbmCloudAuth.clearCache() // no alternative but sledgehammer approach because the IAM cache is keyed by api key
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("org.deleted")))
              } else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("org.not.found", orgId)))
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("org.not.deleted", orgId, t.toString))
            case Failure(t) =>
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("org.not.deleted", orgId, t.toString)))
          })
        }) // end of complete
      }
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
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostNodeErrorResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def orgPostNodesErrorRoute: Route = (path("orgs" / Segment / "search" / "nodes" / "error") & post) { (orgid) =>
    logger.debug(s"Doing POST /orgs/$orgid/search/nodes/error")
    exchAuth(TNode(OrgAndId(orgid,"#").toString),Access.READ) { ident =>
      complete({
        var queryParam = NodesTQ.filter(_.orgid === orgid)
        val userId: String = orgid + "/" + ident.getIdentity
        ident match {
          case _: IUser => if(!(ident.isSuperUser || ident.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
          case _ => ;
        }
        val q = for {
          (n, _) <- NodeErrorTQ.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
        } yield n.nodeId

        db.run(q.result).map({ list =>
          logger.debug("POST /orgs/"+orgid+"/search/nodes/error result size: "+list.size)
          val code: StatusCode = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
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
      new responses.ApiResponse(responseCode = "200", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[AllNodeErrorsInOrgResp])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def nodeGetAllErrorsRoute: Route = (path("orgs" / Segment / "search"  / "nodes" / "error" / "all") & get) { orgid =>
    logger.debug(s"Doing GET /orgs/$orgid/search/nodes/error/all")
    exchAuth(TNode(OrgAndId(orgid,"#").toString),Access.READ) { ident =>
      complete({
        var queryParam = NodesTQ.filter(_.orgid === orgid)
        val userId: String = orgid + "/" + ident.getIdentity
        ident match {
          case _: IUser => if(!(ident.isSuperUser || ident.isAdmin)) queryParam = queryParam.filter(_.owner === userId)
          case _ => ;
        }
        val q = for {
          (ne, _) <- NodeErrorTQ.filter(_.errors =!= "").filter(_.errors =!= "[]") join queryParam on (_.nodeId === _.id)
        } yield (ne.nodeId, ne.errors, ne.lastUpdated)

        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/search/nodes/error/all result size: "+list.size)
          if (list.nonEmpty) {
            val errorsList: ListBuffer[NodeErrorsResp] = ListBuffer[NodeErrorsResp]()
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
  "orgid": "string",
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
          val service: String = reqBody.serviceURL + "_" + reqBody.serviceVersion + "_" + reqBody.serviceArch
          logger.debug("POST /orgs/"+orgid+"/search/nodes/service criteria: "+reqBody.toString)
          val orgService: String = "%|" + reqBody.orgid + "/" + service + "|%"
          var qFilter = NodesTQ.filter(_.orgid === orgid)
          ident match {
            case _: IUser =>
              // if the caller is a normal user then we need to only return node the caller owns
              if(!(ident.isSuperUser || ident.isAdmin)) qFilter = qFilter.filter(_.owner === ident.identityString)
            case _ => ; // nodes can't call this route and agbots don't need an additional filter
          }
          val q = for {
            (n, _) <- qFilter join (NodeStatusTQ.filter(_.runningServices like orgService)) on (_.id === _.nodeId)
          } yield (n.id, n.lastHeartbeat)

          db.run(q.result).map({ list =>
            logger.debug("POST /orgs/"+orgid+"/services/"+service+"/search result size: "+list.size)
            val code: StatusCode = if (list.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
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
    description = "Returns the lastHeartbeat and agreement times for all nodes in this org that do not have a pattern and have had a heartbeat since the specified lastTime. Can be run by an organization admin or agbot (but not a node).",
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
          examples = Array.apply(
            new ExampleObject(
              value = """{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]"
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
        }
      }
    }
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
          val lastTime: String = if (reqBody.lastTime != "") reqBody.lastTime else ApiTime.beginningUTC
          val q = for {
            (n, a) <- NodesTQ.filter(_.orgid === orgid).filter(_.pattern === "").filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ on (_.id === _.nodeId)
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

  
  
  // ====== POST /myorgs ================================
  @POST
  @Path("myorgs")
  @Operation(summary = "Returns the orgs a user can view", description = "Returns all the org definitions in the exchange that match the accounts the caller has access too. Can be run by any user. Request body is the response from /idmgmt/identity/api/v1/users/<user_ID>/accounts API.",
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """[
  {
    "id": "orgid",
    "name": "MyOrg",
    "description": "String Description for Account",
    "createdOn": "2020-09-15T00:20:43.853Z"
  },
  {
    "id": "orgid2",
    "name": "otherOrg",
    "description": "String Description for Account",
    "createdOn": "2020-09-15T00:20:43.853Z"
  }
]"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[List[IamAccountInfo]])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(
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
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def myOrgsPostRoute: Route = (path("myorgs") & post & entity(as[List[IamAccountInfo]])) { reqBody =>
    logger.debug("Doing POST /myorgs")
    // set hint here to some key that states that no org is ok
    // UI should omit org at the beginning of credentials still have them put the slash in there
    exchAuth(TOrg("#"), Access.READ_MY_ORG, hint = "exchangeNoOrgForMultLogin") { _ =>
      complete({
        // getting list of accounts in req body from UI
        val accountsList: ListBuffer[String] = ListBuffer[String]()
        for (account <- reqBody) {accountsList += account.id}
        // filter on the orgs for orgs with those account ids
        val q = OrgsTQ.filter(_.tags.map(tag => tag +>> "cloud_id") inSet accountsList.toSet)
        db.run(q.result).map({ list =>
          logger.debug("POST /myorgs result size: " + list.size)
          val orgs: Map[String, Org] = list.map(a => a.orgId -> a.toOrg).toMap
          val code: StatusCode = if (orgs.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetOrgsResponse(orgs, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/agreements/confirm ===============================
  @POST
  @Path("orgs/{orgid}/agreements/confirm")
  @Operation(
    summary = "Confirms if this agbot agreement is active",
    description = "Confirms whether or not this agreement id is valid, is owned by an agbot owned by this same username, and is a currently active agreement. Can only be run by an agbot or user.",
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
  "agreementId": "ABCDEF"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostAgreementsConfirmRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
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
  def agbotAgreementConfirmRoute: Route = (path("orgs" / Segment / "agreements" / "confirm") & post & entity(as[PostAgreementsConfirmRequest])) { (orgid, reqBody) =>
    exchAuth(TAgbot(OrgAndId(orgid,"#").toString), Access.READ) { ident =>
      complete({
        val creds = ident.creds
        ident match {
          case _: IUser =>
            // the user invoked this rest method, so look for an agbot owned by this user with this agr id
            val agbotAgreementJoin = for {
              (agbot, agr) <- AgbotsTQ joinLeft AgbotAgreementsTQ on (_.id === _.agbotId)
              if agbot.owner === creds.id && agr.map(_.agrId) === reqBody.agreementId
            } yield (agbot, agr)
            db.run(agbotAgreementJoin.result).map({ list =>
              logger.debug("POST /agreements/confirm of "+reqBody.agreementId+" result: "+list.toString)
              // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.owner === owner && agr.agrId === req.agreementId
              if (list.nonEmpty && list.head._2.isDefined && list.head._2.get.state != "") {
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agreement.active")))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agreement.not.found.not.active")))
              }
            })
          case _: IAgbot =>
            // an agbot invoked this rest method, so look for the agbot with this id and for the agbot with this agr id, and see if they are owned by the same user
            val agbotAgreementJoin = for {
              (agbot, agr) <- AgbotsTQ joinLeft AgbotAgreementsTQ on (_.id === _.agbotId)
              if agbot.id === creds.id || agr.map(_.agrId) === reqBody.agreementId
            } yield (agbot, agr)
            db.run(agbotAgreementJoin.result).map({ list =>
              logger.debug("POST /agreements/confirm of "+reqBody.agreementId+" result: "+list.toString)
              if (list.nonEmpty) {
                // this list is tuples of (AgbotRow, Option(AgbotAgreementRow)) in which agbot.id === creds.id || agr.agrId === req.agreementId
                val agbot1 = list.find(r => r._1.id == creds.id).orNull
                val agbot2 = list.find(r => r._2.isDefined && r._2.get.agrId == reqBody.agreementId).orNull
                if (agbot1 != null && agbot2 != null && agbot1._1.owner == agbot2._1.owner && agbot2._2.get.state != "") {
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("agreement.active")))
                } else {
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agreement.not.found.not.active")))
                }
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("agreement.not.found.not.active")))
              }
            })
          case _ => //node should not be calling this route
            (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("access.denied")))
        } //end of match
      }) // end of complete
    } // end of exchAuth
  }

}
