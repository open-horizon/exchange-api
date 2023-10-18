/** Services routes for all of the /orgs/{organization}/business api methods. */
package org.openhorizon.exchangeapi.route.deploymentpolicy

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Access, AuthCache, AuthenticationSupport, DBProcessingError, IUser, OrgAndId, TBusiness, TNode}
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

@Path("/v1/orgs/{organization}/business/policies")
trait BusinessRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def businessRoutes: Route = busPolsGetRoute ~
                              busPolGetRoute ~
                              busPolPostRoute ~
                              busPolPutRoute ~
                              busPolPatchRoute ~
                              busPolDeleteRoute

  /* ====== GET /orgs/{organization}/business/policies ================================ */
  @GET
  @Path("")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolsGetRoute: Route =
    path("orgs" / Segment / "business" / "policies") {
      orgid =>
        get {
          parameter("idfilter".?, "owner".?, "label".?, "description".?) {
            (idfilter,
             owner,
             label,
             description) =>
              exchAuth(TBusiness(OrgAndId(orgid, "*").toString), Access.READ) {
                ident =>
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
          }
        }
    }

  /* ====== GET /orgs/{organization}/business/policies/{policy} ================================ */
  @GET
  @Path("{policy}")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolGetRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & get & parameter("attribute".?)) { (orgid, policy, attribute) =>
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.READ) { _ =>
      complete({
        attribute match {
          case Some(attribute) =>  // Only returning 1 attr of the businessPolicy
            val q = BusinessPoliciesTQ.getAttribute(compositeId, attribute) // get the proper db query for this attribute
            if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.wrong.attribute", attribute)))
            else db.run(q.result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/business/policies/" + policy + " attribute result: " + list.toString)
              if (list.nonEmpty) {
                (HttpCode.OK, GetBusinessPolicyAttributeResponse(attribute, list.head.toString))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              }
            })

          case None =>  // Return the whole business policy resource
            db.run(BusinessPoliciesTQ.getBusinessPolicy(compositeId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/business/policies result size: " + list.size)
              val businessPolicies: Map[String, BusinessPolicy] = list.map(e => e.businessPolicy -> e.toBusinessPolicy).toMap
              val code: StatusCode = if (businessPolicies.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetBusinessPoliciesResponse(businessPolicies, 0))
            })
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{organization}/business/policies/{policy} ===============================
  @POST
  @Path("{policy}")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolPostRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & post & entity(as[PostPutBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
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
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " num owned by " + owner + ": " + num)
              val numOwned: Int = num
              val maxBusinessPolicies: Int = ExchConfig.getInt("api.limits.maxBusinessPolicies")
              if (maxBusinessPolicies == 0 || numOwned <= maxBusinessPolicies) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.getDbInsert(compositeId, orgid, owner).asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.max.limit.buspols", maxBusinessPolicies))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " result: " + v)
              ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
              if (owner != "") AuthCache.putBusinessOwner(compositeId, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
              AuthCache.putBusinessIsPublic(compositeId, isPublic = false)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.created", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("buspol.already.exists", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.created", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.created", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }
  
  // =========== PUT /orgs/{organization}/business/policies/{policy} ===============================
  @PUT
  @Path("{policy}")
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolPutRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & put & entity(as[PostPutBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
              var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break()
                  }
                }
              }
              if (invalidIndex < 0) reqBody.getDbUpdate(compositeId, orgid, owner).asTry
              else {
                val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " result: " + n)
              val numUpdated: Int = n.asInstanceOf[Int]     // i think n is an AnyRef so we have to do this to get it to an int
              if (numUpdated > 0) {
                if (owner != "") AuthCache.putBusinessOwner(compositeId, owner) // currently only users are allowed to update business policy resources, so owner should never be blank
                AuthCache.putBusinessIsPublic(compositeId, isPublic = false)
                resourcechange.ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.updated", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{organization}/business/policies/{policy} ===============================
  @PATCH
  @Path("{policy}")
  @Operation(summary = "Updates 1 attribute of a business policy", description = "Updates one attribute of a business policy. This can only be called by the user that originally created this business policy resource.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolPatchRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & patch & entity(as[PatchBusinessPolicyRequest])) { (orgid, policy, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/business/policies/$policy")
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.buspol.attribute.specified")))
          else {
            val (valServiceIdActions, svcRefs) =
              if (attrName == "service") BusinessPoliciesTQ.validateServiceIds(reqBody.service.get, List())
              else if (attrName == "userInput") BusinessPoliciesTQ.validateServiceIds(BService("", "", "", List(), None), reqBody.userInput.get)
              else (DBIO.successful(Vector()), Vector())
            db.run(valServiceIdActions.asTry.flatMap({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/business/policies" + policy + " service validation: " + v)
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
                logger.debug("PATCH /orgs/" + orgid + "/business/policies/" + policy + " result: " + n)
                val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
                if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                  resourcechange.ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.MODIFIED).insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("buspol.attribute.updated", attrName, compositeId)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("buspol.not.updated", compositeId, t.getMessage)))
            })
          }
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{organization}/business/policies/{policy} ===============================
  @DELETE
  @Path("{policy}")
  @Operation(summary = "Deletes a business policy", description = "Deletes a business policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "policy", in = ParameterIn.PATH, description = "Business Policy name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolDeleteRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment) & delete) { (orgid, policy) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/business/policies/$policy")
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TBusiness(compositeId), Access.WRITE) { _ =>
      complete({
        db.run(BusinessPoliciesTQ.getBusinessPolicy(compositeId).delete.transactionally.asTry.flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/business/policies/" + policy + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeBusinessOwner(compositeId)
              AuthCache.removeBusinessIsPublic(compositeId)
              resourcechange.ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("business.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/business/policies/" + policy + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("business.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("business.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("business.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  
}
