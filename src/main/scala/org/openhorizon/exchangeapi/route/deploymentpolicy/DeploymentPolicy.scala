/** Services routes for all of the /orgs/{organization}/business api methods. */
package org.openhorizon.exchangeapi.route.deploymentpolicy

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, delete, entity, get, parameter, patch, path, post, put, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, Identity, OrgAndId, TBusiness, TNode}
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.table.deploymentpolicy.{BService, BusinessPoliciesTQ, BusinessPolicy}
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Nth, Version}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util._
import scala.util.control.Breaks._

@Path("/v1/orgs/{organization}/business/policies/{policy}")
@io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
trait DeploymentPolicy extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  
  // =========== DELETE /orgs/{organization}/business/policies/{policy} ===============================
  @DELETE
  @Operation(summary = "Deletes a business policy", description = "Deletes a business policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def deleteDeploymentPolicy(deploymentPolicy: String,
                             organization: String,
                             resource: String): Route =
    delete {
      logger.debug(s"Doing DELETE /orgs/$organization/business/policies/$deploymentPolicy")
      complete({
        db.run(BusinessPoliciesTQ.getBusinessPolicy(resource).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + organization + "/business/policies/" + deploymentPolicy + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeBusinessOwner(resource)
              AuthCache.removeBusinessIsPublic(resource)
              resourcechange.ResourceChange(0L, organization, deploymentPolicy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", resource))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + organization + "/business/policies/" + deploymentPolicy + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("business.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("business.policy.not.deleted", resource, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("business.policy.not.deleted", resource, t.toString)))
        })
      })
    }
  
  /* ====== GET /orgs/{organization}/business/policies/{policy} ================================ */
  @GET
  @Operation(summary = "Returns a business policy", description = "Returns the business policy with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name."),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire business policy resource will be returned.")),
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
  def getDeploymentPolicy(deploymentPolicy: String,
                          organization: String,
                          resource: String): Route =
    parameter("attribute".?) {
      attribute =>
        complete({
          attribute match {
            case Some(attribute) =>  // Only returning 1 attr of the businessPolicy
              val q = BusinessPoliciesTQ.getAttribute(resource, attribute) // get the proper db query for this attribute
              if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.wrong.attribute", attribute)))
              else db.run(q.result).map({ list =>
                logger.debug("GET /orgs/" + organization + "/business/policies/" + deploymentPolicy + " attribute result: " + list.toString)
                if (list.nonEmpty) {
                  (HttpCode.OK, GetBusinessPolicyAttributeResponse(attribute, list.head.toString))
                } else {
                  (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
                }
              })
  
            case None =>  // Return the whole business policy resource
              db.run(BusinessPoliciesTQ.getBusinessPolicy(resource).result).map({ list =>
                logger.debug("GET /orgs/" + organization + "/business/policies result size: " + list.size)
                val businessPolicies: Map[String, BusinessPolicy] = list.map(e => e.businessPolicy -> e.toBusinessPolicy).toMap
                val code: StatusCode = if (businessPolicies.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
                (code, GetBusinessPoliciesResponse(businessPolicies, 0))
              })
          }
        })
    }
  
  // =========== PATCH /orgs/{organization}/business/policies/{policy} ===============================
  @PATCH
  @Operation(summary = "Updates 1 attribute of a business policy", description = "Updates one attribute of a business policy. This can only be called by the user that originally created this business policy resource.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value =
              """{
  "label": "name of the business policy",
  "description": "descriptive text",
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",
    "serviceVersions": [
      {
        "version": "1.0.1",
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
    "nodeHealth": {
      "missing_heartbeat_interval": 600,
      "check_agreement_status": 120
    },
    "clusterNamespace": "MyNamespace"
  },
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "string",
      "serviceUrl": "string",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
    }
  ],
  "constraints": [
    "a == b"
  ]
}"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patchDeploymentPolicy(deploymentPolicy: String,
                            organization: String,
                            resource: String): Route =
    patch {
      entity(as[PatchBusinessPolicyRequest]) {
        reqBody =>
          logger.debug(s"Doing PATCH /orgs/$organization/business/policies/$deploymentPolicy")
          
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val (action, attrName) = reqBody.getDbUpdate(resource, organization)
              if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.buspol.attribute.specified")))
              else {
                val (valServiceIdActions, svcRefs) =
                  if (attrName == "service") BusinessPoliciesTQ.validateServiceIds(reqBody.service.get, List())
                  else if (attrName == "userInput") BusinessPoliciesTQ.validateServiceIds(BService("", "", "", List(), None), reqBody.userInput.get)
                  else (DBIO.successful(Vector()), Vector())
                db.run(valServiceIdActions.asTry.flatMap({
                  case Success(v) =>
                    logger.debug("PATCH /orgs/" + organization + "/business/policies" + deploymentPolicy + " service validation: " + v)
                    var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                    breakable {
                      for ((len, index) <- v.zipWithIndex) {
                        if (len <= 0) {
                          invalidIndex = index
                          break()
                        }
                      }
                    }
                    if (invalidIndex < 0) action.transactionally.asTry
                    else {
                      val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                      else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                      DBIO.failed(new Throwable(errStr)).asTry
                    }
                  case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
                }).flatMap({
                  case Success(n) =>
                    // Add the resource to the resourcechanges table
                    logger.debug("PATCH /orgs/" + organization + "/business/policies/" + deploymentPolicy + " result: " + n)
                    val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
                    if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                      resourcechange.ResourceChange(0L, organization, deploymentPolicy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.MODIFIED).insert.asTry
                    } else {
                      DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", resource))).asTry
                    }
                  case Failure(t) => DBIO.failed(t).asTry
                })).map({
                  case Success(v) =>
                    logger.debug("PATCH /orgs/" + organization + "/business/policies/" + deploymentPolicy + " updated in changes table: " + v)
                    (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.attribute.updated", attrName, resource)))
                  case Failure(t: DBProcessingError) =>
                    t.toComplete
                  case Failure(t: org.postgresql.util.PSQLException) =>
                    ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", resource, t.getMessage))
                  case Failure(t) =>
                    (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", resource, t.getMessage)))
                })
              }
            })
          }
      }
    }
  
  // =========== POST /orgs/{organization}/business/policies/{policy} ===============================
  @POST
  @Operation(
    summary = "Adds a business policy",
    description = "Creates a business policy resource. A business policy resource specifies the service that should be deployed based on the specified properties and constraints. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "organization",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "policy",
        in = ParameterIn.PATH,
        description = "Business Policy name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "name of the business policy",
  "description": "descriptive text",
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",
    "serviceVersions": [
      {
        "version": "1.0.1",
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
    "nodeHealth": {
      "missing_heartbeat_interval": 600,
      "check_agreement_status": 120
    },
    "clusterNamespace": "MyNamespace"
  },
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "string",
      "serviceUrl": "string",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
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
  def postDeploymentPolicy(deploymentPolicy: String,
                           identity: Identity,
                           organization: String,
                           resource: String): Route =
    post {
      entity(as[PostPutBusinessPolicyRequest]) {
        reqBody =>
      
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = identity match { case IUser(creds) => creds.id; case _ => "" }
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + organization + "/business/policies" + deploymentPolicy + " service validation: " + v)
              var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break()
                  }
                }
              }
              if (invalidIndex < 0) BusinessPoliciesTQ.getNumOwned(owner).result.asTry
              else {
                val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + organization + "/business/policies" + deploymentPolicy + " num owned by " + owner + ": " + num)
              val numOwned: Int = num
              val maxBusinessPolicies: Int = ExchConfig.getInt("api.limits.maxBusinessPolicies")
              if (maxBusinessPolicies == 0 || numOwned <= maxBusinessPolicies) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.getDbInsert(resource, organization, owner).asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.buspols", maxBusinessPolicies))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + organization + "/business/policies/" + deploymentPolicy + " result: " + v)
              ResourceChange(0L, organization, deploymentPolicy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + organization + "/business/policies/" + deploymentPolicy + " updated in changes table: " + v)
              if (owner != "") AuthCache.putBusinessOwner(resource, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
              AuthCache.putBusinessIsPublic(resource, isPublic = false)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.created", resource)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.already.exists", resource, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.created", resource, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.created", resource, t.getMessage)))
          })
        })
      }
    }
  }
  
  // =========== PUT /orgs/{organization}/business/policies/{policy} ===============================
  @PUT
  @Operation(summary = "Updates a business policy", description = "Updates a business policy resource. This can only be called by the user that created it.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    requestBody = new RequestBody(description = "Business Policy object that needs to be updated. See details in the POST route above.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """
{
  "label": "name of the business policy",
  "description": "descriptive text",
  "service": {
    "name": "mydomain.com.weather",
    "org": "myorg",
    "arch": "amd64",
    "serviceVersions": [
      {
        "version": "1.0.1",
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
    "nodeHealth": {
      "missing_heartbeat_interval": 600,
      "check_agreement_status": 120
    },
    "clusterNamespace": "MyNamespace"
  },
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  "secretBinding": [
    {
      "serviceOrgid": "string",
      "serviceUrl": "string",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
        {"<service-secret-name1>": "<vault-secret-name1>"},
        {"<service-secret-name2>": "<vault-secret-name2>"}
      ],
      "enableNodeLevelSecrets": false
    }
  ],
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing",
      "type": "string"
    }
  ],
  "constraints": [
    "a == b"
  ]
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutBusinessPolicyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def putDeploymentPolicy(deploymentPolicy: String,
                          identity: Identity,
                          organization: String,
                          resource: String): Route =
    put {
      entity(as[PostPutBusinessPolicyRequest]) {
        reqBody =>
          validateWithMsg(reqBody.getAnyProblem) {
            complete({
              val owner: String = identity match { case IUser(creds) => creds.id; case _ => "" }
              val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
              db.run(valServiceIdActions.asTry.flatMap({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/business/policies" + deploymentPolicy + " service validation: " + v)
                  var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                  breakable {
                    for ((len, index) <- v.zipWithIndex) {
                      if (len <= 0) {
                        invalidIndex = index
                        break()
                      }
                    }
                  }
                  if (invalidIndex < 0) reqBody.getDbUpdate(resource, organization, owner).asTry
                  else {
                    val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                    else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                    DBIO.failed(new Throwable(errStr)).asTry
                  }
                case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
              }).flatMap({
                case Success(n) =>
                  // Add the resource to the resourcechanges table
                  logger.debug("POST /orgs/" + organization + "/business/policies/" + deploymentPolicy + " result: " + n)
                  val numUpdated: Int = n.asInstanceOf[Int]     // i think n is an AnyRef so we have to do this to get it to an int
                  if (numUpdated > 0) {
                    if (owner != "") AuthCache.putBusinessOwner(resource, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
                    AuthCache.putBusinessIsPublic(resource, isPublic = false)
                    resourcechange.ResourceChange(0L, organization, deploymentPolicy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                  } else {
                    DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", resource))).asTry
                  }
                case Failure(t) => DBIO.failed(t).asTry
              })).map({
                case Success(v) =>
                  logger.debug("POST /orgs/" + organization + "/business/policies/" + deploymentPolicy + " updated in changes table: " + v)
                  (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.updated", resource)))
                case Failure(t: DBProcessingError) =>
                  t.toComplete
                case Failure(t: org.postgresql.util.PSQLException) =>
                  ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", resource, t.getMessage))
                case Failure(t) =>
                  (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", resource, t.getMessage)))
              })
            })
          }
      }
    }
  
  
  val deploymentPolicy: Route =
    path("orgs" / Segment / "business" / "policies" / Segment) {
      (organization, deploymentPolicy) =>
        val resource: String = OrgAndId(organization, deploymentPolicy).toString
        
        (delete | patch | put) {
          exchAuth(TBusiness(resource), Access.WRITE) {
            identity =>
              deleteDeploymentPolicy(deploymentPolicy, organization, resource) ~
                patchDeploymentPolicy(deploymentPolicy, organization, resource) ~
                putDeploymentPolicy(deploymentPolicy, identity, organization, resource)
          }
        } ~
        get {
          exchAuth(TBusiness(resource), Access.READ) {
            _ =>
              getDeploymentPolicy(deploymentPolicy, organization, resource)
          }
        } ~
        post {
          exchAuth(TBusiness(resource), Access.CREATE) {
            identity =>
              postDeploymentPolicy(deploymentPolicy, identity, organization, resource)
          }
        }
    }
}
