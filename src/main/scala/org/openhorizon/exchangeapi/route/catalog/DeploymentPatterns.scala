package org.openhorizon.exchangeapi.route.catalog

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, parameter, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TPattern}
import org.openhorizon.exchangeapi.route.deploymentpattern.GetPatternsResponse
import org.openhorizon.exchangeapi.table.deploymentpattern.{Pattern, PatternsTQ}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext


@Path("/v1/catalog/patterns")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait DeploymentPatterns extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /catalog/patterns ================================
  @GET
  @Path("patterns")
  @Operation(summary = "Returns patterns in the IBM catalog", description = "Returns public pattern definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
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
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getDeploymentPatterns(@Parameter(hidden = true) identity: Identity2): Route =
    parameter("clusternamespace".?,
              "description".?,
              "idfilter".?,
              "label".?,
              "organization".?,
              "owner".?,
              "public".as[Boolean].optional) {
      (clusterNamespace,
       description,
       idfilter,
       label,
       organization,
       owner,
       public) =>
        logger.debug(s"GET /catalog/${if (organization.isDefined) organization.get + "/" else ""}patterns?clusterNamespace=${clusterNamespace.getOrElse("None")},description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},owner=${owner.getOrElse("None")},public=${public.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        val getDeploymentPatternsAll: Query[((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]), ((String, Option[String], String, String, String, String, Boolean, String, String, String), String), Seq] =
          for {
            patterns: ((Rep[String], Rep[Option[String]], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String]), Rep[String]) <-
              PatternsTQ.filterOpt(organization)((deployment_patterns, organization) => if (organization.contains("%")) deployment_patterns.orgid like organization else deployment_patterns.orgid === organization)
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
          db.run(Compiled(getDeploymentPatternsAll).result.transactionally).map {
            result =>
              logger.debug("GET /catalog/patterns - result size: " + result.size)
              implicit val formats: Formats = DefaultFormats
              
              if (result.nonEmpty)
                (StatusCodes.OK, GetPatternsResponse(result.map(deployment_patterns => (deployment_patterns._2 -> new Pattern(deployment_patterns._1))).toMap))
              else
                (StatusCodes.NotFound, GetPatternsResponse())
          }
        }
    }
  
  def deploymentPatternsCatalog(identity: Identity2): Route =
    // old: ../v1/catalog/patterns
    // new: ../v1/deployment/patterns
    path(("catalog" | "deployment") / "patterns") {
      get {
        exchAuth(TPattern(OrgAndId("*","*").toString), Access.READ_ALL_PATTERNS, validIdentity = identity) {
          _ =>
            getDeploymentPatterns(identity)
        }
      }
    }
}
