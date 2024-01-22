/** Services routes for all of the /orgs/{organization}/patterns api methods. */
package org.openhorizon.exchangeapi.route.deploymentpattern

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.route.node.{PatternNodeResponse, PostPatternSearchResponse}
import org.openhorizon.exchangeapi.route.organization.{NodeHealthHashElement, PostNodeHealthRequest, PostNodeHealthResponse}
import org.openhorizon.exchangeapi.table.deploymentpattern.key.PatternKeysTQ
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServices, Pattern, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth, RouteUtils, Version}
import org.openhorizon.exchangeapi.auth
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util._
import scala.util.control.Breaks._

@Path("/v1/orgs/{organization}/patterns")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
trait DeploymentPatterns extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  /* ====== GET /orgs/{organization}/patterns ================================ */
  @GET
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this id (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this owner (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this public setting"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this label (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this description (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "clusternamespace", in = ParameterIn.QUERY, required = false, description = "Filter results to only include deployment patterns with this cluster namespace (can include '%' for wildcard - the URL encoding for '%' is '%25')")),
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
      "secretBinding": [],
      "agreementProtocols": [
        {
          "name": "Basic"
        }
      ],
      "lastUpdated": "2019-05-14T16:34:34.194Z[UTC]",
      "clusterNamespace": "MyNamespace"
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
  def getDeploymentPatterns(identity: Identity,
                            organization: String): Route =
    parameter("idfilter".?,
              "owner".?,
              "public".?,
              "label".?,
              "description".?,
              "clusternamespace".?) {
      (idfilter,
       owner,
       public,
       label,
       description,
       clusterNamespace) =>
        validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
          complete({
            //var q = PatternsTQ.subquery
            var q = PatternsTQ.getAllPatterns(organization)
            // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
            clusterNamespace.foreach(namespace => { if (namespace.contains("%")) q = q.filter(_.clusterNamespace like namespace) else q = q.filter(_.clusterNamespace === namespace) })
            description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })
            idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
            label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
            owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
            public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
            
            db.run(q.result).map({ list =>
              logger.debug("GET /orgs/"+organization+"/patterns result size: "+list.size)
              val patterns: Map[String, Pattern] = list.filter(e => identity.getOrg == e.orgid || e.public || identity.isSuperUser || identity.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
              val code: StatusCode = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetPatternsResponse(patterns, 0))
            })
          }) // end of complete
        }
    }
  
  val deploymentPatterns: Route =
    path("orgs" / Segment / "patterns") {
      organization =>
        get {
          exchAuth(TPattern(OrgAndId(organization, "*").toString), Access.READ) {
            identity =>
              getDeploymentPatterns(identity, organization)
          }
        }
    }
}
