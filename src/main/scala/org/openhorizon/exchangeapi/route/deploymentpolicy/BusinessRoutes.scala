/** Services routes for all of the /orgs/{orgid}/business api methods. */
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
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.DBProcessingError
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, IUser, Nth, OrgAndId, TBusiness, TNode, Version}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/business/policies routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/business/policies */
final case class GetBusinessPoliciesResponse(businessPolicy: Map[String,BusinessPolicy], lastIndex: Int)
final case class GetBusinessPolicyAttributeResponse(attribute: String, value: String)

object BusinessUtils {
  def getAnyProblem(service: BService): Option[String] = {
    // Ensure the references to the service are not null
    if (service.name==null || service.org==null || service.arch==null) return Some(ExchMsg.translate("no.service.ref.specified.for.service"))
    // Check they specified at least 1 service version
    if (service.serviceVersions==null || service.serviceVersions.isEmpty) return Some(ExchMsg.translate("no.version.specified.for.service2"))
    // Check the version syntax
    for (sv <- service.serviceVersions) {
      if (!Version(sv.version).isValid) return Some(ExchMsg.translate("version.not.valid.format", sv.version))
    }
    None
  }
}

/** Input format for POST/PUT /orgs/{orgid}/business/policies/<bus-pol-id> */
final case class PostPutBusinessPolicyRequest(label: String,
                                              description: Option[String],
                                              service: BService,
                                              userInput: Option[List[OneUserInputService]],
                                              secretBinding: Option[List[OneSecretBindingService]],
                                              properties: Option[List[OneProperty]],
                                              constraints: Option[List[String]]) {
  require(label != null &&
          service!=null &&
          service.name != null &&
          service.org != null &&
          service.arch != null &&
          service.serviceVersions != null)
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = { BusinessUtils.getAnyProblem(service) }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { BusinessPoliciesTQ.validateServiceIds(service, userInput.getOrElse(List())) }

  // The nodeHealth field is optional, so fill in a default in service if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
  def defaultNodeHealth(service: BService): BService = {
    if (service.nodeHealth.nonEmpty) return service
    val agrChkDefault: Int = ExchConfig.getInt("api.defaults.businessPolicy.check_agreement_status")
    val hbDefault: Int = ExchConfig.getInt("api.defaults.businessPolicy.missing_heartbeat_interval")
    val nodeHealth2: Option[Map[String, Int]] = Some(Map("missing_heartbeat_interval" -> hbDefault, "check_agreement_status" -> agrChkDefault)) // provide defaults for node health
   
    BService(arch = service.arch,
             clusterNamespace = service.clusterNamespace,
             name = service.name,
             nodeHealth = nodeHealth2,
             org = service.org,
             serviceVersions = service.serviceVersions)
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def getDbInsert(businessPolicy: String,
                  orgid: String,
                  owner: String): DBIO[_] = {
    BusinessPolicyRow(businessPolicy = businessPolicy,
                      constraints = write(constraints),
                      created = ApiTime.nowUTC,
                      description = description.getOrElse(label),
                      label = label,
                      lastUpdated = ApiTime.nowUTC,
                      orgid = orgid,
                      owner = owner,
                      properties = write(properties),
                      secretBinding = write(secretBinding),
                      service = write(defaultNodeHealth(service)),
                      userInput = write(userInput)).insert
  }

  def getDbUpdate(businessPolicy: String, orgid: String, owner: String): DBIO[_] = {
    (for {
       deploymentPolicy <-
         BusinessPoliciesTQ.filter(_.businessPolicy === businessPolicy)
                           .map(policy =>
                             (policy.constraints,
                              policy.description,
                              policy.label,
                              policy.lastUpdated,
                              policy.properties,
                              policy.secretBinding,
                              policy.service,
                              policy.userInput))
     } yield(deploymentPolicy))
      .update((write(constraints),
               description.getOrElse(label),
               label,
               ApiTime.nowUTC,
               write(properties),
               write(secretBinding),
               write(defaultNodeHealth(service)),
               write(userInput)))
  }
}

final case class PatchBusinessPolicyRequest(label: Option[String],
                                            description: Option[String],
                                            service: Option[BService],
                                            userInput: Option[List[OneUserInputService]],
                                            secretBinding:Option[List[OneSecretBindingService]] ,
                                            properties: Option[List[OneProperty]],
                                            constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ if (service.isDefined) BusinessUtils.getAnyProblem(service.get)
    else None
  }

  /** Returns a tuple of the db action to update parts of the businessPolicy, and the attribute name being updated. */
  def getDbUpdate(businessPolicy: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated: String = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this businessPolicy
    constraints match { case Some(con) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.constraints,d.lastUpdated)).update((businessPolicy, write(con), lastUpdated)), "constraints"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.description,d.lastUpdated)).update((businessPolicy, desc, lastUpdated)), "description"); case _ => ; }
    label match { case Some(lab) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.label,d.lastUpdated)).update((businessPolicy, lab, lastUpdated)), "label"); case _ => ; }
    properties match { case Some(prop) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.properties,d.lastUpdated)).update((businessPolicy, write(prop), lastUpdated)), "properties"); case _ => ; }
    secretBinding match {case Some(bind) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.secretBinding,d.lastUpdated)).update((businessPolicy, write(bind), lastUpdated)), "secretBinding"); case _ => ; }
    service match { case Some(svc) => return ((for {d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.service,d.lastUpdated)).update((businessPolicy, write(svc), lastUpdated)), "service"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- BusinessPoliciesTQ if d.businessPolicy === businessPolicy } yield (d.businessPolicy,d.userInput,d.lastUpdated)).update((businessPolicy, write(input), lastUpdated)), "userInput"); case _ => ; }
    (null, null)
  }
}


/** Input for business policy-based search for nodes to make agreements with. */
final case class PostBusinessPolicySearchRequest(changedSince: Long = 0L,
                                                 nodeOrgids: Option[List[String]] = None,
                                                 numEntries: Option[Int] = None,
                                                 session: Option[String] = None,
                                                 startIndex: Option[String] = None)       // Not used.

// Tried this to have names on the tuple returned from the db, but didn't work...
final case class BusinessPolicySearchHashElement(nodeType: String, publicKey: String, noAgreementYet: Boolean)

final case class BusinessPolicyNodeResponse(id: String, nodeType: String, publicKey: String)
final case class PostBusinessPolicySearchResponse(nodes: List[BusinessPolicyNodeResponse], offsetUpdated: Boolean = false)
final case class PolicySearchResponseDesync(agbot: String, offset: Option[String], session: Option[String])



/** Implementation for all of the /orgs/{orgid}/business/policies routes */
@Path("/v1/orgs/{orgid}/business/policies")
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
                              busPolDeleteRoute ~
                              busPolPostSearchRoute

  /* ====== GET /orgs/{orgid}/business/policies ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all business policies", description = "Returns all business policy definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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

  /* ====== GET /orgs/{orgid}/business/policies/{policy} ================================ */
  @GET
  @Path("{policy}")
  @Operation(summary = "Returns a business policy", description = "Returns the business policy with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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

  // =========== POST /orgs/{orgid}/business/policies/{policy} ===============================
  @POST
  @Path("{policy}")
  @Operation(
    summary = "Adds a business policy",
    description = "Creates a business policy resource. A business policy resource specifies the service that should be deployed based on the specified properties and constraints. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
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
         ]
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
  
  // =========== PUT /orgs/{orgid}/business/policies/{policy} ===============================
  @PUT
  @Path("{policy}")
  @Operation(summary = "Updates a business policy", description = "Updates a business policy resource. This can only be called by the user that created it.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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
         ]
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
                ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.CREATEDMODIFIED).insert.asTry
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

  // =========== PATCH /orgs/{orgid}/business/policies/{policy} ===============================
  @PATCH
  @Path("{policy}")
  @Operation(summary = "Updates 1 attribute of a business policy", description = "Updates one attribute of a business policy. This can only be called by the user that originally created this business policy resource.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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
      ]
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
                  ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.MODIFIED).insert.asTry
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

  // =========== DELETE /orgs/{orgid}/business/policies/{policy} ===============================
  @DELETE
  @Path("{policy}")
  @Operation(summary = "Deletes a business policy", description = "Deletes a business policy. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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
              ResourceChange(0L, orgid, policy, ResChangeCategory.POLICY, false, ResChangeResource.POLICY, ResChangeOperation.DELETED).insert.asTry
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

  // ======== POST /org/{orgid}/business/policies/{policy}/search ========================
  @POST
  @Path("{policy}/search")
  @Operation(
    summary = "Returns matching nodes for this business policy",
    description = "Returns the matching nodes for this business policy that do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "policy",
        in = ParameterIn.PATH,
        description = "Pattern name."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "changedSince": "123456L",
  "nodeOrgids": ["org1", "org2", "..."],
  "numEntries": 100,
  "session": "token"
}"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostBusinessPolicySearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        content = Array(new Content(mediaType = "application/json",
                                    schema = new Schema(implementation = classOf[PostBusinessPolicySearchResponse]))),
        description = "response body",
        responseCode = "201"
      ),
      new responses.ApiResponse(
        description = "bad request",
        responseCode = "400"
      ),
      new responses.ApiResponse(
        description = "invalid credentials",
        responseCode = "401"
      ),
      new responses.ApiResponse(
        description = "access denied",
        responseCode = "403"
      ),
      new responses.ApiResponse(
        description = "not found",
        responseCode = "404"
      ),
      new responses.ApiResponse(
        content = Array(new Content(mediaType = "application/json",
                                    schema = new Schema(implementation = classOf[PolicySearchResponseDesync]))),
        description = "old session",
        responseCode = "409"
      )
    )
  )
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment policy")
  def busPolPostSearchRoute: Route = (path("orgs" / Segment / "business" / "policies" / Segment / "search") & post & entity(as[PostBusinessPolicySearchRequest])) { (orgid, policy, reqBody) =>
    val compositeId: String = OrgAndId(orgid, policy).toString
    exchAuth(TNode(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validateWithMsg(if(!((!(reqBody.changedSince < 0L)) && (reqBody.numEntries.isEmpty || !(reqBody.numEntries.getOrElse(-1) < 0)))) Some(ExchMsg.translate("bad.input")) else None) {
        complete({
          val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
          var searchSvcUrl = ""    // a composite value (org/url), will be set later in the db.run()
          db.run(BusinessPoliciesTQ.getService(compositeId).result.flatMap({ list =>
            if (list.nonEmpty) {
              // Finding the service was successful, form the query for the nodes for the next step
              val service: BService = BusinessPoliciesTQ.getServiceFromString(list.head)    // we should have found only 1 business pol service string, now parse it to get the BService object
              
              // Bypass filter on architecture.
              val optArch: Option[String] =
                if(service.arch.equals("") ||
                   service.arch.equals("*"))
                  None
                else
                  Some(service.arch)
              
              searchSvcUrl = OrgAndId(service.org, service.name).toString
              
              // Build the DB query that includes the pagination and node/agreement filtering
              val pagination =
                for {
                  // Grab the offset and session that is in the DB from the last query of this agbot and policy
                  // Note: the offset is a lastUpdated UTC timestamp, whereas reqBody.changedSince is Unix epoch seconds, but they have the same meaning.
                  currentOffsetSession <- SearchOffsetPolicyTQ.getOffsetSession(ident.identityString, compositeId).result.headOption // returns Option[(offset, session)]
                  
                  currentOffset: Option[String] =
                    if (currentOffsetSession.isDefined)
                      currentOffsetSession.get._1
                    else
                      None
                  
                  currentSession: Option[String] =
                  if (currentOffsetSession.isDefined)
                    currentOffsetSession.get._2
                  else
                    None
                  
                  // Figure out what our offset is going to be in this DB query.
                  offset: Option[String] =
                  if (currentOffset.isEmpty && 0L < reqBody.changedSince) // New workflow for abgot and policy, use the changedSince they passed in
                    Some(ApiTime.thenUTC(reqBody.changedSince))
                  else if (currentOffset.isDefined &&
                           currentSession.isDefined &&
                           reqBody.session.isDefined &&
                           currentSession.get.equals(reqBody.session.get)) // the session they passed in equals the session stored in the DB, so use that offset
                    currentOffset
                  else
                    None // No previous pagination, so we don't limit how far back we look at the nodes
                  
                  // If this is a desynchronized agbot (one using a different session than is stored in the DB), setting desynchronization will cause us to return 409 to let the agbot know
                  // it is out of sync with the other agbots, and we will also return the current session/offset that they should begin using.
                  // In the case of catastrophic failure of the entire set of agbots, we will return Http code 409 to each of them, and they will each be redirected to using the session again.
                  desynchronization: Option[PolicySearchResponseDesync] =
                    if (currentSession.isDefined &&
                        reqBody.session.isDefined &&
                        !currentSession.get.equals(reqBody.session.get))
                      Some(PolicySearchResponseDesync(agbot = ident.identityString,
                                                      offset = currentOffset,
                                                      session = currentSession))
                    else
                      None
                  
                  /*
                    Filter the nodes in the DB to return the nodes that:
                      - the arch matches the service arch (including wildcards)
                      - are not pre-created nodes from which the agent has never communicated to us yet
                      - have changed since the offset or changedSince
                      - are in the given list of node orgs
                      - pattern is not set
                      - have publicKey set (are registered)
                      - does not have an agreement for this service with a non-empty state
                    Notes:
                      - about Slick usage: joinLeft returns node rows even if they don't have any agreements (which is why the agreement cols are Option() )
                      - Live-lock will occur if the resulting number of nodes with the same lastUpdated is greater than the size of the page being returned. In that case we have no choice
                        but to keep having the agbots call us with the same offset until that is no longer the case (because the agbots have processed some nodes and made agreements with them)
                  */
                  nodes = NodesTQ
                                 .filterOpt(optArch)((node, arch) => node.arch === arch)
                                 .filter(_.lastHeartbeat.isDefined) // do not return pre-created nodes from which the agent has never communicated to us yet
                                 // Note: since the timestamp for lastUpdated/changedSince/offset is an inexact boundary (i.e. there can be multiple nodes with the same lastlastUpdated value,
                                 //     some of which weren't returned last time), we err on the side of possibly returning some nodes we already returned, rather that possibly missing some nodes.
                                 .filterOpt(offset)((node, changedSince) => !(node.lastUpdated < changedSince)) // here changedSince is either currentOffset or converted reqBody.changedSince
                                 .filter(_.orgid inSet nodeOrgids)
                                 .filter(_.pattern === "")
                                 .filter(_.publicKey =!= "")
                                 .filterOpt(desynchronization)((node, _) => node.id === "") // node.id will never by the empty string, so is this a way of returning 0 nodes in this case??
                                 .map(node => (node.id, node.lastUpdated, node.nodeType, node.publicKey))
                                 /*
                                  The joinLeft will create rows like: node.id, node.lastUpdated, node.nodeType, node.publicKey, agreement.agrSvcUrl, agreement.nodeId, agreement.state
                                  with a few caveats:
                                    - only agreements which are for searchSvcUrl will be included
                                    - if there is no agreement for searchSvcUrl for a node, the 3 agreement fields will be None
                                    - if there are multiple agreements for searchSvcUrl for a node (not supposed to be, but could be), the node will be repeated in the output, but will be filtered out later on
                                 */
                                 .joinLeft(NodeAgreementsTQ
                                                           .filter(_.agrSvcUrl === searchSvcUrl) // only join with agreements that are for this service, so we can filter those out below
                                                           .map(agreement => (agreement.agrSvcUrl, agreement.nodeId, agreement.state)))
                                 .on((node, agreement) => node._1 === agreement._2) // (node.id === agreements.nodeId)
                                 .filter ({
                                   // Since we joined with agreements for this service, now we only keep nodes in our list that don't have any associated agreement or the agreement state is empty
                                   case (_, agreement) =>
                                     agreement.map(_._2).isEmpty ||                        // agreement.nodeId
                                     agreement.map(_._1).getOrElse("") === "" ||  // agreement.agrSvcUrl
                                     agreement.map(_._3).getOrElse("") === ""     // agreement.state
                                 })
                                 .sortBy(r => (r._1._2.asc, r._1._1.asc, r._2.getOrElse(("", "", ""))._1.asc.nullsFirst)) // (node.lastUpdated ASC, node.id ASC, agreements.agrSvcUrl ASC NULLS FIRST)
                                 .map(r => (r._1._1, r._1._2, r._1._3, r._1._4))                                          // (node.id, node.lastUpdated, node.nodeType, node.publicKey)
                  
                  // If paginating then limit the query to that number of rows, else return everything.
                  nodesWoAgreements <- {
                    if (reqBody.numEntries.isDefined)
                      nodes.take(reqBody.numEntries.getOrElse(0))
                    else
                      nodes
                    }.result.map(List[(String, String, String, String)])
                  
                  // Decide what offset should be stored in our DB for the next agbot call.
                  updateOffset: Option[String] =
                    if (desynchronization.isDefined) // return what we currently have.
                      currentOffset
                    else if (reqBody.numEntries.isDefined) {
                      if (nodesWoAgreements.nonEmpty &&
                          (currentOffset.isEmpty ||
                           (currentOffset.get < nodesWoAgreements.lastOption.get._2 && // nodesWoAgreements.lastOption.get._2 is the lastUpdated field of the last node in the list
                            nodesWoAgreements.size.equals(reqBody.numEntries.get)))) // Normal pagination case: we filled a page and the lastUpdated at the end of the page is newer than the offset.
                        Some(nodesWoAgreements.lastOption.get._2)
                      //todo: i think implied in this next condition is lastUpdated of the last row is the same as offset, because if that wasn't the case the previous if stmt would have been true. But that logic is pretty complex because there are several other parts to the condition, so it would be better to explicitly test for that.
                      else if (currentOffset.isDefined &&
                               currentSession.isDefined &&
                               reqBody.session.isDefined &&
                               currentSession.get.equals(reqBody.session.get) &&
                               nodesWoAgreements.size.equals(reqBody.numEntries.get)) // Last row has the same lastUpdated as the current offset (i.e. all rows in the page have same lastUpdated).
                        currentOffset // I think this is what is called live-lock above. We have no choice but to return the same offset as we used this time
                      else // We didn't fill the page, so we are done with this session/workflow.
                        None
                    }
                    else // We gave them everything, so the current session is over
                      None
                  
                  // Return in the response body whether or not this query resulted in the offset being changed.
                  isOffsetUpdated: Boolean =
                   if (desynchronization.isDefined ||
                       updateOffset.isEmpty ||
                       (currentOffset.isDefined && currentOffset.get.equals(updateOffset.get)))
                      false
                    else
                      true
                  
                  // Decide what session should be stored in our DB for the next call
                  updateSession: Option[String] =
                    if (desynchronization.isDefined) // Write back what we currently have.
                      currentSession
                    else if (reqBody.numEntries.isDefined &&
                             reqBody.session.isDefined) {
                      if (currentSession.isEmpty &&
                          nodesWoAgreements.nonEmpty &&
                          nodesWoAgreements.size.equals(reqBody.numEntries.get)) // New workflow. We didn't have a saved session and we only returned partial results, so save the session they gave us.
                        reqBody.session
                      else if (currentSession.isDefined &&
                               currentSession.get.equals(reqBody.session.get) &&
                               nodesWoAgreements.size.equals(reqBody.numEntries.get)) // Continue workflow.
                        currentSession
                      else // End of workflow.
                        None
                    }
                    else // No defined workflow. Either they didn't give us numEntries or didn't give us session
                      None
                    
                    // Clear/continue/set/update offset and session for the next call.
                    _ <- SearchOffsetPolicyTQ.setOffsetSession(ident.identityString, updateOffset, compositeId, updateSession)
                } yield (desynchronization, nodesWoAgreements, isOffsetUpdated)
              
              // Finally run the DB query
              pagination.transactionally.asTry // Prevent dirty reads/writes by using transactionally.
            }
            else DBIO.failed(new Throwable(ExchMsg.translate("business.policy.not.found", compositeId))).asTry
          })).map({
            case Success(results) =>
              if(results._2.nonEmpty) { // results.nodesWoAgreements.nonEmpty.
                (HttpCode.POST_OK,
                  PostBusinessPolicySearchResponse(
                    results._2.map( // results.nodesWoAgreements
                      node =>
                        BusinessPolicyNodeResponse(
                          node._1,                              // node.id
                          node._3 match {                       // node.nodeType
                            case "" => NodeType.DEVICE.toString // "" -> "device"
                            case _ => node._3                   // Passthrough
                          },
                          node._4)),                            // node.publicKey
                    results._3)) // results.isOffsetUpdated
              }
              // Throw Http code 409 - Conflict, return no results.
              else if (results._1.isDefined) // results.desynchronization
                (HttpCode.ALREADY_EXISTS2, results._1)
              else
                (HttpCode.NOT_FOUND, PostBusinessPolicySearchResponse(List[BusinessPolicyNodeResponse](), results._3)) // results.isOffsetUpdated
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }
}
