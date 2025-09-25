/** Services routes for all of the /orgs api methods. */
package org.openhorizon.exchangeapi.route.organization

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats, JValue}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, TOrg}
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import org.openhorizon.exchangeapi.table.organization.{Org, OrgRow, Orgs, OrgsTQ}
import org.openhorizon.exchangeapi.utility.ExchMsg
import slick.lifted.CompiledStreamingExecutable

import scala.collection.immutable._
import scala.concurrent.{ExecutionContext, Future}



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
  @Operation(summary = "Returns all organizations", description = "Returns some or all organization definitions. Can be run by any resource archetype (Agreement Bot, Node, User). Will at minimum always return the organization of the caller.",
    parameters = Array(
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include organizations with this description (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "organization", in = ParameterIn.QUERY, required = false, description = "Filter results to only include organizations are this, or are like this (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include organizations with this org type. Currently the only supported org type for this route is 'IBM'.",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM"))))),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include organizations with this label (can include % for wildcard - the URL encoding for % is %25)")),
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
  def getOrganizations(@Parameter(hidden = true) description: Option[String],
                       @Parameter(hidden = true) identity: Identity2,
                       @Parameter(hidden = true) label: Option[String],
                       @Parameter(hidden = true) organization: Option[String],
                       @Parameter(hidden = true) orgType: Option[String]): Route =
    {
      logger.debug(s"GET /orgs?label=${label.getOrElse("None")},organization=${organization.getOrElse("None")},orgtype=${orgType.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
      
      validate(orgType.isEmpty || orgType.get == "IBM", ExchMsg.translate("org.get.orgtype")) {
        val getOrgsAll: CompiledStreamingExecutable[Query[((Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Option[JValue]]), Rep[String]), ((String, String, String, String, String, String, Option[JValue]), String), Seq], Seq[((String, String, String, String, String, String, Option[JValue]), String)], ((String, String, String, String, String, String, Option[JValue]), String)] =
          for {
            organizations <-
              Compiled(OrgsTQ.filterIf(identity.isOrgAdmin || identity.isStandardUser)(organizations => organizations.orgid === identity.organization || organizations.orgid === "IBM")
                             .filterIf(identity.isAgbot || identity.isNode)(organizations => organizations.orgid === identity.organization)
                             .filterOpt(description)((organizations, description) => (if (description.contains("%")) organizations.description like description else organizations.description === description))
                             .filterOpt(label)((organizations, label) => (if (label.contains("%")) organizations.label like label else organizations.label === label))
                             .filterOpt(organization)((organizations, org) => (if (org.contains("%")) organizations.orgid like org else organizations.orgid === org))
                             .filterOpt(orgType)((organizations, orgType) => organizations.orgid === orgType)
                             .map(organizations =>
                                    ((organizations.description,
                                      organizations.heartbeatIntervals,
                                      organizations.label,
                                      organizations.lastUpdated,
                                      organizations.limits,
                                      // organizations.orgid,
                                      organizations.orgType,
                                      organizations.tags),
                                     organizations.orgid))
                             .sortBy(_._2.asc))
          } yield organizations
        
        complete {
          db.run(getOrgsAll.result.transactionally).map {
            result =>
              Future { logger.debug(s"GET /orgs?label=${label.getOrElse("None")},organization=${organization.getOrElse("None")},orgtype=${orgType.getOrElse("None")} - ${result.size}") }
              implicit val formats: Formats = DefaultFormats
              
              if (result.nonEmpty)
                (StatusCodes.OK, GetOrgsResponse(result.map(organizations => organizations._2 -> new Org(organizations._1)).toMap))
              else
                (StatusCodes.NotFound, GetOrgsResponse())
          }
        }
      }
    }
  
  def organizations(identity: Identity2): Route =
    path("orgs") {
      get {
        parameter("description".?,
                  "label".?,
                  "organization".?,
                  "orgtype".?) {
          (description,
           label,
           organization,
           orgType) =>
            // If filter is orgType=IBM then it is a different access required than reading all orgs
            // val access: Access.Value = if (orgType.getOrElse("").contains("IBM")) Access.READ_IBM_ORGS else Access.READ_OTHER_ORGS
            
            exchAuth(TOrg(identity.organization), Access.READ, validIdentity = identity) {
              _ =>
                getOrganizations(description, identity, label, organization, orgType)
            }
        }
      }
    }
}
