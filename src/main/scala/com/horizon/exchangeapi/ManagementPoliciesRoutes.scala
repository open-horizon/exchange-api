/** Services routes for all of the /orgs/{orgid}/managementpolicy api methods. */
package com.horizon.exchangeapi

import java.sql.Timestamp

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization.write
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

import com.horizon.exchangeapi.tables._
import com.horizon.exchangeapi.auth._

//====== These are the input and output structures for /orgs/{orgid}/managementpolicy routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/managementpolicies */
final case class GetManagementPoliciesResponse(managementPolicy: Map[String, ManagementPolicy], lastIndex: Int)
final case class GetManagementPolicyAttributeResponse(attribute: String, value: String)

/** Input format for POST/PUT /orgs/{orgid}/managementpolicies/<mgmt-pol-id> */
final case class PostPutManagementPolicyRequest(label: String,
                                                description: Option[String],
                                                properties: Option[List[OneProperty]],
                                                constraints: Option[List[String]],
                                                patterns: Option[List[String]],
                                                enabled: Boolean,
                                                start: String = "now",
                                                startWindow: Long = 0,
                                                agentUpgradePolicy: Option[AgentUpgradePolicy])  {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if (constraints.nonEmpty && patterns.nonEmpty) return Some(ExchMsg.translate("mgmtpol.constraints.or.patterns"))
    None
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(managementPolicy: String, orgid: String, owner: String): DBIO[_] = {
    ManagementPolicyRow(allowDowngrade = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.allowDowngrade else false,
                        constraints = write(constraints),
                        created = ApiTime.nowUTC,
                        description = description.getOrElse(label),
                        enabled = enabled,
                        label = label,
                        lastUpdated = ApiTime.nowUTC,
                        managementPolicy = managementPolicy,
                        manifest = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.manifest else "",
                        orgid = orgid,
                        owner = owner,
                        patterns = write(patterns),
                        properties = write(properties),
                        start = start,
                        startWindow = if(startWindow < 0) 0 else startWindow).insert
  }

  def getDbUpdate(managementPolicy: String, orgid: String, owner: String): DBIO[_] = {
    ManagementPolicyRow(allowDowngrade = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.allowDowngrade else false,
                        constraints = write(constraints),
                        created = ApiTime.nowUTC,
                        description = description.getOrElse(label),
                        enabled = enabled,
                        label = label,
                        lastUpdated = ApiTime.nowUTC,
                        managementPolicy = managementPolicy,
                        manifest = if(agentUpgradePolicy.nonEmpty) agentUpgradePolicy.get.manifest else "",
                        orgid = orgid,
                        owner = owner,
                        patterns = write(patterns),
                        properties = write(properties),
                        start = start,
                        startWindow = if(startWindow < 0) 0 else startWindow).update
  }
}

//todo: add patch class

/** Implementation for all of the /orgs/{orgid}/managementpolicies routes */
@Path("/v1/orgs/{orgid}/managementpolicies")
trait ManagementPoliciesRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def managementPoliciesRoutes: Route = mgmtPolsGetRoute ~ mgmtPolGetRoute ~ mgmtPolPostRoute ~ mgmtPolPutRoute /*~ mgmtPolPatchRoute*/ ~ mgmtPolDeleteRoute

  /* ====== GET /orgs/{orgid}/managementpolicies ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all node management policies", description = "Returns all management policy definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include management policies with this description (can include % for wildcard - the URL encoding for % is %25)")),
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
  def mgmtPolsGetRoute: Route = (path("orgs" / Segment / "managementpolicies") & get & parameter("idfilter".?, "owner".?, "label".?, "description".?)) { (orgid, idfilter, owner, label, description) =>
    exchAuth(TManagementPolicy(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      complete({
        var q = ManagementPoliciesTQ.getAllManagementPolicies(orgid)
        // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
        idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.managementPolicy like id) else q = q.filter(_.managementPolicy === id) })
        owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
        label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
        description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

        db.run(q.result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/managementpolicies result size: "+list.size)
          val managementPolicy: Map[String, ManagementPolicy] = list.filter(e => ident.getOrg == e.orgid || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.managementPolicy -> e.toManagementPolicy).toMap
          val code: StatusCode with Serializable = if (managementPolicy.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetManagementPoliciesResponse(managementPolicy, 0))
        })
      }) // end of complete
  } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/managementpolicies/{nmpid} ================================ */
  @GET
  @Path("{nmpid}")
  @Operation(summary = "Returns a node management policy", description = "Returns the management policy with the specified id. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "nmpid", in = ParameterIn.PATH, description = "Node management policy name."),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire management policy resource will be returned.")),
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
  def mgmtPolGetRoute: Route = (path("orgs" / Segment / "managementpolicies" / Segment) & get & parameter("attribute".?)) { (orgid, nmpid, attribute) =>
    val compositeId: String = OrgAndId(orgid, nmpid).toString
    exchAuth(TManagementPolicy(compositeId), Access.READ) { _ =>
      complete({
        attribute match {
          case Some(attribute) =>  // Only returning 1 attr of the managementPolicy
            val q = ManagementPoliciesTQ.getAttribute(compositeId, attribute) // get the proper db query for this attribute
            if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.wrong.attribute", attribute)))
            else db.run(q.result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/managementpolicies/" + nmpid + " attribute result: " + list.toString)
              if (list.nonEmpty) {
                (HttpCode.OK, GetManagementPolicyAttributeResponse(attribute, list.head.toString))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              }
            })

          case None =>  // Return the whole management policy resource
            db.run(ManagementPoliciesTQ.getManagementPolicy(compositeId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/managementpolicies result size: " + list.size)
              val managementPolicies: Map[String, ManagementPolicy] = list.map(e => e.managementPolicy -> e.toManagementPolicy).toMap //mapping management policy object to string
              val code: StatusCode with Serializable = if (managementPolicies.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetManagementPoliciesResponse(managementPolicies, 0))
            })
        }
      }) // end of complete
  } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/managementpolicies/{nmpid} ===============================
  @POST
  @Path("{nmpid}")
  @Operation(
    summary = "Adds a node management policy",
    description = "Creates a node management policy resource. A node management policy controls the updating of the edge node agents. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "nmpid",
        in = ParameterIn.PATH,
        description = "Management Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the management policy",
  "description": "descriptive text",
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
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
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutManagementPolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
  def mgmtPolPostRoute: Route = (path("orgs" / Segment / "managementpolicies" / Segment) & post & entity(as[PostPutManagementPolicyRequest])) { (orgid, nmpid, reqBody) =>
    val compositeId: String = OrgAndId(orgid, nmpid).toString
    exchAuth(TManagementPolicy(compositeId), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          db.run(ManagementPoliciesTQ.getNumOwned(owner).result.asTry.flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/managementpolicies" + nmpid + " num owned by " + owner + ": " + num)
              val numOwned: Int = num
              val maxManagementPolicies: Int = ExchConfig.getInt("api.limits.maxManagementPolicies")
              if (maxManagementPolicies == 0 || numOwned <= maxManagementPolicies) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.getDbInsert(compositeId, orgid, owner).asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.mgmtpols", maxManagementPolicies))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/managementpolicies/" + nmpid + " result: " + v)
              ResourceChange(0L, orgid, nmpid, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/managementpolicies/" + nmpid + " updated in changes table: " + v)
              if (owner != "") AuthCache.putManagementPolicyOwner(compositeId, owner) // currently only users are allowed to update management policy resources, so owner should never be blank
              AuthCache.putManagementPolicyIsPublic(compositeId, isPublic = false)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("mgmtpol.created", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("mgmtpol.already.exists", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("mgmtpol.not.created", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.not.created", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/managementpolicies/{policy} ===============================
  @PUT
  @Path("{nmpid}")
  @Operation(
    summary = "Updates a node management policy",
    description = "Updates a node management policy resource. A node management policy controls the updating of the edge node agents. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "nmpid",
        in = ParameterIn.PATH,
        description = "Management Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the management policy",
  "description": "descriptive text",
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
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
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutManagementPolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
      ),
      new responses.ApiResponse(
        responseCode = "400",
        description = "bad input"
      ),
      new responses.ApiResponse(
        responseCode = "401",
        description = "invalid credentials"
      ),
      new responses.ApiResponse(
        responseCode = "403",
        description = "access denied"
      ),
      new responses.ApiResponse(
        responseCode = "404",
        description = "not found"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
  def mgmtPolPutRoute: Route = (path("orgs" / Segment / "managementpolicies" / Segment) & put & entity(as[PostPutManagementPolicyRequest])) { (orgid, nmpid, reqBody) =>
    val compositeId: String = OrgAndId(orgid, nmpid).toString
    exchAuth(TManagementPolicy(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          db.run(reqBody.getDbUpdate(compositeId, orgid, owner).asTry.flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/managementpolicies/" + nmpid + " result: " + v)
              ResourceChange(0L, orgid, nmpid, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.MODIFIED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/managementpolicies/" + nmpid + " updated in changes table: " + v)
              if (owner != "") AuthCache.putManagementPolicyOwner(compositeId, owner) // currently only users are allowed to update management policy resources, so owner should never be blank
              AuthCache.putManagementPolicyIsPublic(compositeId, isPublic = false)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("mgmtpol.updated", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("mgmtpol.already.exists", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("mgmtpol.not.created", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("mgmtpol.not.created", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/managementpolicies/{nmpid} ===============================
  @DELETE
  @Path("{nmpid}")
  @Operation(summary = "Deletes a management policy", description = "Deletes a management policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "nmpid", in = ParameterIn.PATH, description = "Management Policy name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "management policy")
  def mgmtPolDeleteRoute: Route = (path("orgs" / Segment / "managementpolicies" / Segment) & delete) { (orgid, nmpid) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/managementpolicies/$nmpid")
    val compositeId: String = OrgAndId(orgid, nmpid).toString
    exchAuth(TManagementPolicy(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(ManagementPoliciesTQ.getManagementPolicy(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/managementpolicies/" + nmpid + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeManagementPolicyOwner(compositeId)
              AuthCache.removeManagementPolicyIsPublic(compositeId)
              ResourceChange(0L, orgid, nmpid, ResChangeCategory.MGMTPOLICY, false, ResChangeResource.MGMTPOLICY, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("management.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/managementpolicies/" + nmpid + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("management.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("management.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("management.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

}
