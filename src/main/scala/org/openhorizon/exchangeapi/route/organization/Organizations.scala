/** Services routes for all of the /orgs api methods. */
package org.openhorizon.exchangeapi.route.organization

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.checkerframework.checker.units.qual.t
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi
import org.openhorizon.exchangeapi.auth.cloud.{IamAccountInfo, IbmCloudAuth}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IAgbot, INode, IUser, Identity, OrgAndId, TAction, TAgbot, TNode, TOrg}
import org.openhorizon.exchangeapi.route.agreementbot.PostAgreementsConfirmRequest
import org.openhorizon.exchangeapi.route.node.{PostNodeErrorResponse, PostServiceSearchRequest, PostServiceSearchResponse}
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import org.openhorizon.exchangeapi.table._
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
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ApiUtils, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, RouteUtils}
import org.openhorizon.exchangeapi.{ExchangeApi, table}

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


/** Routes for /orgs */
@Path("/v1/orgs")
@io.swagger.v3.oas.annotations.tags.Tag(name = "organization")
trait Organizations extends JacksonSupport with AuthenticationSupport {
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
  def getOrganizations(ident: Identity,
                       label: Option[String],
                       orgType: Option[String]): Route =
    {
      logger.debug(s"Doing GET /orgs with orgType:$orgType, label:$label")
      
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
        })
      }
    }
  
  val organizations: Route =
    path("orgs") {
      get {
        parameter("orgtype".?, "label".?) {
          (orgType, label) =>
            // If filter is orgType=IBM then it is a different access required than reading all orgs
            val access: Access.Value = if (orgType.getOrElse("").contains("IBM")) Access.READ_IBM_ORGS else Access.READ_OTHER_ORGS
            
            exchAuth(TOrg("*"), access) {
              identity =>
                getOrganizations(identity, label, orgType)
            }
        }
      }
    }
}
