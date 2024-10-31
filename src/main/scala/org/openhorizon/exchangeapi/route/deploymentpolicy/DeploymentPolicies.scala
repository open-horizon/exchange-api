package org.openhorizon.exchangeapi.route.deploymentpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, parameter, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, OrgAndId, TBusiness}
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BusinessPoliciesTQ, BusinessPolicy}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext


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
  def getDeploymentPolicies(@Parameter(hidden = true) ident: Identity,
                            @Parameter(hidden = true) orgid: String): Route =
    parameter("idfilter".?, "owner".?, "label".?, "description".?) {
      (idfilter,
       owner,
       label,
       description) =>
        complete({
          var q = BusinessPoliciesTQ.getAllBusinessPolicies(orgid) // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          description.foreach(desc => {
            if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc)
          })
          idfilter.foreach(id => {
            if (id.contains("%")) q = q.filter(_.businessPolicy like id) else q = q.filter(_.businessPolicy === id)
          })
          label.foreach(lab => {
            if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab)
          })
          owner.foreach(owner => {
            if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner)
          })
          
          db.run(q.result).map({ list =>
            logger.debug("GET /orgs/" + orgid + "/business/policies result size: " + list.size)
            val businessPolicy: Map[String, BusinessPolicy] = list.filter(e => ident.getOrg == e.orgid || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.businessPolicy -> e.toBusinessPolicy).toMap
            val code: StatusCode = if (businessPolicy.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetBusinessPoliciesResponse(businessPolicy, 0))
          })
        })
    }
  
  val deploymentPolicies: Route =
    path("orgs" / Segment / ("business" | "deployment") / "policies") {
      organization =>
        get {
          exchAuth(TBusiness(OrgAndId(organization, "*").toString), Access.READ) {
            identity =>
              getDeploymentPolicies(identity, organization)
          }
        }
    }
}
