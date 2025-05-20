/** Services routes for all of the /orgs/{organization}/managementpolicy api methods. */
package org.openhorizon.exchangeapi.route.managementpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ExchangeApiApp.exchAuth
import org.openhorizon.exchangeapi.auth.{Access, AccessDeniedException, AuthCache, AuthenticationSupport, DBProcessingError, IUser, Identity2, OrgAndId, TManagementPolicy}
import org.openhorizon.exchangeapi.table.managementpolicy.{ManagementPoliciesTQ, ManagementPolicy}
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling, HttpCode}
import slick.jdbc.PostgresProfile.api._

import java.nio.file.AccessDeniedException
import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util._

@Path("/v1/orgs/{organization}/managementpolicies")
@io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
trait ManagementPolicies extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  /* ====== GET /orgs/{organization}/managementpolicies ================================ */
  @GET
  @Operation(summary = "Returns all node management policies", description = "Returns all management policy definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this description (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "manifest", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this manifest (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this owner (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "managementPolicy": {
    "orgid/mymgmtpol": {
      "owner": "string",
      "label": "string",
      "description": "string",
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
      "patterns": [
        "pat1"
      ],
      "enabled": true,
      "agentUpgradePolicy": {
        "atLeastVersion": "current",
        "start": "now",
        "duration": 0
      },
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
            schema = new Schema(implementation = classOf[GetManagementPoliciesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getManagementPolicies(@Parameter(hidden = true) identity: Identity2,
                            @Parameter(hidden = true) organization: String): Route =
    parameter("idfilter".?,
              "owner".?,
              "label".?,
              "description".?,
              "manifest".?) {
      (idfilter,
       owner,
       label,
       description,
       manifest) =>
        logger.debug(s"GET /orgs/${organization}/managementpolicies?description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},manifest=${manifest.getOrElse("None")},owner=${owner.getOrElse("None")} - By ${identity.resource}:${identity.role}")
        
        complete({
          val getAllManagementPolicies =
            for {
              managementPolicies <-
                Compiled(ManagementPoliciesTQ.getAllManagementPolicies(organization)
                                             .filterOpt(description)(
                                               (managementPolicy, description) =>
                                                 if (description.contains("%"))
                                                   managementPolicy.description like description
                                                 else
                                                   managementPolicy.description === description)
                                             .filterOpt(idfilter)(
                                               (managementPolicy, id) =>
                                                 if (id.contains("%"))
                                                   managementPolicy.managementPolicy like id
                                                 else
                                                   managementPolicy.managementPolicy === id)
                                             .filterOpt(label)(
                                               (managementPolicy, label) =>
                                                 if (label.contains("%"))
                                                   managementPolicy.label like label
                                                 else
                                                   managementPolicy.label === label)
                                             .filterOpt(manifest)(
                                               (managementPolicy, manifest) =>
                                                 if (manifest.contains("%"))
                                                   managementPolicy.manifest like manifest
                                                 else
                                                   managementPolicy.manifest === manifest)
                                             .filterOpt(owner)(
                                               (managementPolicy, owner) =>
                                                 if (owner.contains("%"))
                                                   managementPolicy.owner.toString() == owner
                                                 else
                                                   managementPolicy.owner.toString() == owner)
                                             .sortBy(_.managementPolicy.asc.nullsLast))
            } yield(managementPolicies)
          
          db.run(getAllManagementPolicies.result.transactionally).map({
            managementPolicies =>
              logger.debug("GET /orgs/" + organization + "/managementpolicies result size: " + managementPolicies.size)
              val code: StatusCode =
                if (managementPolicies.nonEmpty)
                  StatusCodes.OK
                else
                  StatusCodes.NotFound
              
              (code, GetManagementPoliciesResponse(managementPolicies.map(managementPolicy => managementPolicy.managementPolicy -> managementPolicy.toManagementPolicy).toMap, 0))
          })
        })
    }
  
  def managementPolicies(identity: Identity2): Route =
    path("orgs" / Segment / "managementpolicies") {
      organization =>
        get {
          exchAuth(TManagementPolicy(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
            _ =>
              getManagementPolicies(identity, organization)
          }
        }
    }
}
