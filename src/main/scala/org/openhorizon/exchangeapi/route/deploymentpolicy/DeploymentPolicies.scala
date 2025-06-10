package org.openhorizon.exchangeapi.route.deploymentpolicy

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
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, Identity2, OrgAndId, TBusiness}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BusinessPoliciesTQ, BusinessPolicy}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import slick.jdbc.PostgresProfile.api._

import scala.annotation.unused
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/orgs/{organization}/business/policies")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
trait DeploymentPolicies extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  /* ====== GET /orgs/{organization}/business/policies ================================ */
  @GET
  @Operation(summary = "Returns all business policies", description = "Returns all business policy definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include Deployment Policies with this Identifier (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include Deployment Policies with this Owner (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include Deployment Policies with this Label (can include '%' for wildcard - the URL encoding for '%' is '%25')"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include Deployment Policies with this Description (can include '%' for wildcard - the URL encoding for '%' is '%25')")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "businessPolicy": {
    "orgid/mybuspol": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "service": {
        "name": "string",
        "org": "string",
        "arch": "string",
        "serviceVersions": [
          {
            "version": "1.2.3",
            "priority": null,
            "upgradePolicy": null
          }
        ],
        "nodeHealth": {
          "missing_heartbeat_interval": 600,
          "check_agreement_status": 120
        },
        "clusterNamespace": "MyNamespace"
      },
      "userInput": [],
      "secretBinding": [],
      "properties": [
        {
          "name": "string",
          "type": "string",
          "value": "string"
        }
      ],
      "constraints": [
        "a == b"
      ],
      "lastUpdated": "string",
      "created": "string"
    }
  },
  "lastIndex": 0
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetBusinessPoliciesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getDeploymentPolicies(@Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String): Route =
    parameter("idfilter".?, "owner".?, "label".?, "description".?) {
      (idfilter,
       owner,
       label,
       description) =>
        logger.debug(s"GET /orgs/${organization}/business/policies?description=${description.getOrElse("None")}, idfilter=${idfilter.getOrElse("None")}, label=${label.getOrElse("None")}, owner=${owner.getOrElse("None")} - By ${identity.resource}:${identity.role}")
        
        val getAllDeployPolicies: Query[((Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String]), Rep[String]), ((String, String, String, String, String, String, String, String, String, String), String), Seq] =
          for {
            deployPolicies: ((Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String]), Rep[String]) <-
              BusinessPoliciesTQ.filter(_.orgid === organization)
                                .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(policies => policies.orgid === identity.organization)
                                .filterOpt(description)((policies, description) => (if (description.contains("%")) policies.description like description else policies.description === description))
                                .filterOpt(idfilter)((policies, idfilter) => (if (idfilter.contains("%")) policies.businessPolicy like idfilter else policies.businessPolicy === idfilter))
                                .filterOpt(label)((policies, label) => (if (label.contains("%")) policies.label like label else policies.label === label))
                                .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                .on(_.owner === _._2)
                                .filterOpt(owner)((policies, owner) => (if (owner.contains("%")) (policies._2._1 ++ "/" ++ policies._2._3) like owner else (policies._2._1 ++ "/" ++ policies._2._3) === owner))
                                .map(policies =>
                                      ((policies._1.constraints,
                                        policies._1.created,
                                        policies._1.description,
                                        policies._1.label,
                                        policies._1.lastUpdated,
                                        (policies._2._1 ++ "/" ++ policies._2._3),
                                        policies._1.properties,
                                        policies._1.secretBinding,
                                        policies._1.service,
                                        policies._1.userInput),
                                       policies._1.businessPolicy))
          } yield deployPolicies
        
        complete({
          db.run(Compiled(getAllDeployPolicies).result.transactionally.asTry).map {
            case Success(deployPolRecords) =>
              logger.debug("GET /orgs/" + organization + "/business/policies result size: " + deployPolRecords.size)
              val defaultFormats: Formats = DefaultFormats
              
              if (deployPolRecords.nonEmpty)
                (StatusCodes.OK, GetBusinessPoliciesResponse(deployPolRecords.map(results => results._2 -> new BusinessPolicy(results._1)(defaultFormats)).toMap))
              else
                (StatusCodes.NotFound, GetBusinessPoliciesResponse())
            case Failure(exception) =>
              logger.error(cause = exception, message = "GET /orgs/" + organization + "/business/policies")
              (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
          }
        })
    }
  
  def deploymentPolicies(identity: Identity2): Route =
    path("orgs" / Segment / ("business" | "deployment") / "policies") {
      organization =>
        get {
          exchAuth(TBusiness(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
            _ =>
              getDeploymentPolicies(identity, organization)
          }
        }
    }
}
