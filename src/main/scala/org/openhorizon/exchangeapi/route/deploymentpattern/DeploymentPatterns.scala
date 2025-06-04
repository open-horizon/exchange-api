/** Services routes for all of the /orgs/{organization}/patterns api methods. */
package org.openhorizon.exchangeapi.route.deploymentpattern

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, parameter, path, validate, _}
import org.apache.pekko.http.scaladsl.server.Route
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
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth, RouteUtils, Version}
import org.openhorizon.exchangeapi.auth
import org.openhorizon.exchangeapi.table.user.UsersTQ
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
  def getDeploymentPatterns(@Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String): Route =
    parameter("idfilter".?,
              "owner".?,
              "public".as[Boolean].optional,
              "label".?,
              "description".?,
              "clusternamespace".?) {
      (idfilter,
       owner,
       public,
       label,
       description,
       clusterNamespace) =>
        logger.debug(s"GET /orgs/${organization}/patterns?clusterNamespace=${clusterNamespace.getOrElse("None")},description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},owner=${owner.getOrElse("None")},public=${public.getOrElse("None")} - By ${identity.resource}:${identity.role}")
        implicit val formats: Formats = DefaultFormats
        val getDeploymentPatterns: Query[((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]), ((String, Option[String], String, String, String, String, Boolean, String, String, String), String), Seq] =
          for {
            patterns: ((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]) <-
              PatternsTQ.filter(deployment_patterns => deployment_patterns.orgid === organization)
                        .filterIf(identity.isOrgAdmin || identity.isStandardUser)(deployment_patterns => deployment_patterns.orgid === identity.organization || deployment_patterns.orgid === "IBM" || deployment_patterns.public)
                        .filterOpt(clusterNamespace)((deployment_patterns, clusterNamespace) => if (clusterNamespace.contains("%")) deployment_patterns.clusterNamespace like clusterNamespace else deployment_patterns.clusterNamespace === clusterNamespace)
                        .filterOpt(description)((deployment_patterns, description) => if (description.contains("%")) deployment_patterns.description like description else deployment_patterns.description === description)
                        .filterOpt(idfilter)((deployment_patterns, resource) => if (resource.contains("%")) deployment_patterns.pattern like resource else deployment_patterns.pattern === resource)
                        .filterOpt(label)((deployment_patterns, label) => if (label.contains("%")) deployment_patterns.label like label else deployment_patterns.label === label)
                        .filterOpt(public)((deployment_patterns, public) => deployment_patterns.public === public)
                        .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                        .on(_.owner === _._2)
                        .filterOpt(owner)((deployment_patterns, owner) => if (owner.contains("%")) (deployment_patterns._2._1 ++ "/" ++ deployment_patterns._2._3) like owner else (deployment_patterns._2._1 ++ "/" ++ deployment_patterns._2._3) === owner)
                .map(deployment_patterns =>
                       ((deployment_patterns._1.agreementProtocols,
                         deployment_patterns._1.clusterNamespace,
                         deployment_patterns._1.description,
                         deployment_patterns._1.label,
                         deployment_patterns._1.lastUpdated,
                         (deployment_patterns._2._1 ++ "/" ++ deployment_patterns._2._3),
                         // deployment_patterns._1.orgid,
                         deployment_patterns._1.public,
                         deployment_patterns._1.secretBinding,
                         deployment_patterns._1.services,
                         deployment_patterns._1.userInput),
                        deployment_patterns._1.pattern))
          } yield patterns
        
          complete {
            db.run(Compiled(getDeploymentPatterns).result).map {
              result =>
                logger.debug("GET /orgs/"+organization+"/patterns result size: " + result.size)
                
                if (result.nonEmpty)
                  (StatusCodes.OK, GetPatternsResponse(result.map(e => (e._2 -> new Pattern(e._1))).toMap))
                else
                  (StatusCodes.NotFound, GetPatternsResponse())
            }
          }
    }
  
  def deploymentPatterns(identity: Identity2): Route =
    path(("catalog" | "orgs") / Segment / ("patterns" | "deployment" ~ Slash ~ "patterns")) {
      organization =>
        get {
          exchAuth(TPattern(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
            _ =>
              getDeploymentPatterns(identity, organization)
          }
        }
    }
}
