package org.openhorizon.exchangeapi.route.catalog

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, parameter, validate, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, OrgAndId, TPattern, TService}
import org.openhorizon.exchangeapi.route.deploymentpattern.GetPatternsResponse
import org.openhorizon.exchangeapi.route.service.{GetServicesResponse, GetServicesUtils}
import org.openhorizon.exchangeapi.table.deploymentpattern.{Pattern, PatternsTQ}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.service.{Service, ServicesTQ}
import org.openhorizon.exchangeapi.utility.ExchMsg
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext



@Path("/v1/catalog/{organization}/patterns")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait OrganizationDeploymentPatterns extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /catalog/{organization}/patterns ================================ */
  @GET
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
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
    }
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
  def getOrganizationDeploymentPatterns(identity: Identity,
                                        organization: String): Route =
    parameter("idfilter".?,
              "owner".?,
              "public".?,
              "label".?,
              "description".?) {
      (idfilter,
       owner,
       public,
       label,
       description) =>
        validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
          complete({
            logger.debug("ORGID: "+ organization)
            //var q = PatternsTQ.subquery
            var q = PatternsTQ.getAllPatterns(organization)
            // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
            idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
            owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
            public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
            label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
            description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })
  
            val svcQuery =
              for {
                (_, svc) <-
                  OrgsTQ.getOrgidsOfType("IBM") join PatternsTQ on ((o, s) => {o === s.orgid && s.public})
              } yield svc
  
            var allPatterns : Map[String, Pattern] = null
            db.run(q.result.flatMap({ list =>
              logger.debug("GET /catalog/"+organization+"/patterns org result size: "+list.size)
              val patterns: Map[String, Pattern] = list.filter(e => identity.getOrg == e.orgid || e.public || identity.isSuperUser || identity.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
              allPatterns = patterns
              svcQuery.result
            })).map({ list =>
              logger.debug("GET /orgs/"+organization+"/patterns IBM result size: "+list.size)
              val patterns: Map[String, Pattern] = list.map(a => a.pattern -> a.toPattern).toMap
              allPatterns = allPatterns ++ patterns
              val code: StatusCode = if (allPatterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetPatternsResponse(allPatterns, 0))
            })
          })
        }
    }
  
  val organizationDeploymentPatterns: Route =
    path("catalog" / Segment / "patterns") {
      organization =>
        get {
          exchAuth(TPattern(OrgAndId(organization, "*").toString), Access.READ) {
            identity =>
              getOrganizationDeploymentPatterns(identity, organization)
          }
        }
    }
}
