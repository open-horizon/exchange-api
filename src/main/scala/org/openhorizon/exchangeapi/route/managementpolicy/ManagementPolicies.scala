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
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchMsg, ExchangePosgtresErrorHandling}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.CompiledStreamingExecutable

import java.nio.file.AccessDeniedException
import scala.collection.immutable._
import scala.concurrent.{ExecutionContext, Future}
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
        Future { logger.debug(s"GET /orgs/${organization}/managementpolicies?description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},manifest=${manifest.getOrElse("None")},owner=${owner.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})") }
        
        val getAllManagementPolicies: CompiledStreamingExecutable[Query[((Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Long]), Rep[String]), ((Boolean, String, String, String, Boolean, String, String, String, String, String, String, String, Long), String), Seq], Seq[((Boolean, String, String, String, Boolean, String, String, String, String, String, String, String, Long), String)], ((Boolean, String, String, String, Boolean, String, String, String, String, String, String, String, Long), String)] =
          for {
            managementPolicies <-
              Compiled(ManagementPoliciesTQ.filter(management_policies => management_policies.orgid === organization)
                                             .filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(management_policies => management_policies.orgid === identity.organization)
                                             .filterOpt(description)((managementPolicy, description) =>
                                                 if (description.contains("%"))
                                                   managementPolicy.description like description
                                                 else
                                                   managementPolicy.description === description)
                                             .filterOpt(idfilter)((managementPolicy, id) =>
                                                 if (id.contains("%"))
                                                   managementPolicy.managementPolicy like id
                                                 else
                                                   managementPolicy.managementPolicy === id)
                                             .filterOpt(label)((managementPolicy, label) =>
                                                 if (label.contains("%"))
                                                   managementPolicy.label like label
                                                 else
                                                   managementPolicy.label === label)
                                             .filterOpt(manifest)((managementPolicy, manifest) =>
                                                 if (manifest.contains("%"))
                                                   managementPolicy.manifest like manifest
                                                 else
                                                   managementPolicy.manifest === manifest)
                                            .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                                            .on(_.owner === _._2)
                                            .filterOpt(owner)((management_policies, owner) => if (owner.contains("%")) (management_policies._2._1 ++ "/" ++ management_policies._2._3) like owner else (management_policies._2._1 ++ "/" ++ management_policies._2._3) === owner)
                                            .map(management_policies =>
                                                  ((management_policies._1.allowDowngrade,
                                                    management_policies._1.constraints,
                                                    management_policies._1.created,
                                                    management_policies._1.description,
                                                    management_policies._1.enabled,
                                                    management_policies._1.label,
                                                    management_policies._1.lastUpdated,
                                                    management_policies._1.manifest,
                                                    //management_policies._1.orgid,
                                                    (management_policies._2._1 ++ "/" ++ management_policies._2._3),
                                                    management_policies._1.patterns,
                                                    management_policies._1.properties,
                                                    management_policies._1.start,
                                                    management_policies._1.startWindow),
                                                   management_policies._1.managementPolicy))
                                            .sortBy(_._2.asc.nullsLast))
          } yield managementPolicies
          
        complete {
          implicit val defaultFormats: Formats = DefaultFormats.withLong
          db.run(getAllManagementPolicies.result.transactionally.asTry).map {
            case Success(managementPolicies) =>
              Future { logger.debug(s"GET /orgs/${organization}/managementpolicies?description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},manifest=${manifest.getOrElse("None")},owner=${owner.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - result size: $managementPolicies.size") }
              
              if (managementPolicies.nonEmpty)
                (StatusCodes.OK, GetManagementPoliciesResponse(managementPolicies.map(managementPolicy => managementPolicy._2 -> (new ManagementPolicy(managementPolicy._1)(defaultFormats))).toMap))
              else {
                Future { logger.debug(s"GET /orgs/${organization}/managementpolicies?description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},manifest=${manifest.getOrElse("None")},owner=${owner.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - managementPolicies.nonEmpty:${managementPolicies.nonEmpty} - ${(StatusCodes.NotFound, write(GetManagementPoliciesResponse()))}") }
                (StatusCodes.NotFound, GetManagementPoliciesResponse())
              }
            case Failure(exception) =>
              Future { logger.debug(s"GET /orgs/${organization}/managementpolicies?description=${description.getOrElse("None")},idfilter=${idfilter.getOrElse("None")},label=${label.getOrElse("None")},manifest=${manifest.getOrElse("None")},owner=${owner.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")}) - ${exception.toString} - ${(StatusCodes.InternalServerError, write(ApiResponse(ApiRespType.INTERNAL_ERROR, exception.getMessage)))}") }
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, exception.getMessage))
          }
        }
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
