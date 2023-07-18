/** Services routes for all of the /orgs/{orgid}/patterns api methods. */
package org.openhorizon.exchangeapi.route.deploymentpattern

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.route.node.{PatternNodeResponse, PostPatternSearchResponse}
import org.openhorizon.exchangeapi.route.organization.{NodeHealthHashElement, PostNodeHealthRequest, PostNodeHealthResponse}
import org.openhorizon.exchangeapi.table.{organization, _}
import org.openhorizon.exchangeapi.table.deploymentpattern.{PServices, Pattern, PatternKeysTQ, PatternsTQ}
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.{NodeType, NodesTQ}
import org.openhorizon.exchangeapi.table.organization.{OrgsTQ, ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.{Access, ApiRespType, ApiResponse, ApiTime, AuthCache, AuthenticationSupport, ExchConfig, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, IUser, Nth, OrgAndId, RouteUtils, TNode, TPattern, Version, auth}
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util._
import scala.util.control.Breaks._

@Path("/v1/orgs/{orgid}/patterns")
trait PatternsRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def patternsRoutes: Route =
    patternDeleteKeyRoute ~
    patternDeleteKeysRoute ~
    patternDeleteRoute ~
    patternGetKeyRoute ~
    patternGetKeysRoute ~
    patternGetRoute ~
    patternNodeHealthRoute ~
    patternPatchRoute ~
    patternPostRoute ~
    patternPostSearchRoute ~
    patternPutKeyRoute ~
    patternPuttRoute ~
    patternsGetRoute

  /* ====== GET /orgs/{orgid}/patterns ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternsGetRoute: Route = (path("orgs" / Segment / "patterns") & get & parameter("idfilter".?, "owner".?, "public".?, "label".?, "description".?, "clusternamespace".?)) { (orgid, idfilter, owner, public, label, description, clusterNamespace) =>
    exchAuth(TPattern(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
        complete({
          //var q = PatternsTQ.subquery
          var q = PatternsTQ.getAllPatterns(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          clusterNamespace.foreach(namespace => { if (namespace.contains("%")) q = q.filter(_.clusterNamespace like namespace) else q = q.filter(_.clusterNamespace === namespace) })
          description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })
          idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
          label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          
          db.run(q.result).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/patterns result size: "+list.size)
            val patterns: Map[String, Pattern] = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
            val code: StatusCode = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetPatternsResponse(patterns, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/patterns/{pattern} ================================ */
  @GET
  @Path("{pattern}")
  @Operation(summary = "Returns a pattern", description = "Returns the pattern with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire pattern resource will be returned")),
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternGetRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & get & parameter("attribute".?)) { (orgid, pattern, attribute) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.READ) { _ =>
      complete({
        attribute match {
          case Some(attribute) =>  // Only returning 1 attr of the pattern
            val q = PatternsTQ.getAttribute(compositeId, attribute) // get the proper db query for this attribute
            if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.attr.not.in.pattern", attribute)))
            else db.run(q.result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/patterns/" + pattern + " attribute result: " + list.toString)
              if (list.nonEmpty) {
                (HttpCode.OK, GetPatternAttributeResponse(attribute, list.head.toString))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              }
            })

          case None =>  // Return the whole pattern resource
            db.run(PatternsTQ.getPattern(compositeId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/patterns result size: " + list.size)
              val patterns: Map[String, Pattern] = list.map(e => e.pattern -> e.toPattern).toMap
              val code: StatusCode = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetPatternsResponse(patterns, 0))
            })
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/patterns/{pattern} ===============================
  @POST
  @Path("{pattern}")
  @Operation(
    summary = "Adds a pattern",
    description = "Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
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
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
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
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
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
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
            {"<service-secret-name1>": "<vault-secret-name1>"},
            {"<service-secret-name2>": "<vault-secret-name2>"}
         ]
      }
   ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutPatternRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternPostRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & post & entity(as[PostPutPatternRequest])) { (orgid, pattern, reqBody) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          // Get optional agbots that should be updated with this new pattern
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " service validation: " + v)
              var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break()
                  }
                }
              }
              if (invalidIndex < 0) OrgsTQ.getAttribute(orgid, "orgType").result.asTry //getting orgType from orgid
              else {
                val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(orgName) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " checking public field and orgType of " + compositeId + ": " + orgName)
              val orgType: Seq[Any] = orgName
              val publicField: Boolean = reqBody.public.getOrElse(false)
              if ((publicField && orgType.head == "IBM") || !publicField) { // pattern is public and owner is IBM so ok, or pattern isn't public at all so ok
                PatternsTQ.getNumOwned(owner).result.asTry
              } else DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " num owned by " + owner + ": " + num)
              val numOwned: Int = num
              val maxPatterns: Int = ExchConfig.getInt("api.limits.maxPatterns")
              if (maxPatterns == 0 || numOwned <= maxPatterns) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.toPatternRow(compositeId, orgid, owner).insert.asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.max.patterns", maxPatterns))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + " result: " + v)
              val publicField: Boolean = reqBody.public.getOrElse(false)
              ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + " updated in changes table: " + v)
              if (owner != "") AuthCache.putPatternOwner(compositeId, owner) // currently only users are allowed to update pattern resources, so owner should never be blank
              AuthCache.putPatternIsPublic(compositeId, reqBody.public.getOrElse(false))
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.created", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.already.exists", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.created", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.created", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/patterns/{pattern} ===============================
  @PUT
  @Path("{pattern}")
  @Operation(summary = "Adds a pattern", description = "Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
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
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
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
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
            {"<service-secret-name1>": "<vault-secret-name1>"},
            {"<service-secret-name2>": "<vault-secret-name2>"}
         ]
      }
   ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutPatternRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource created - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternPuttRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & put & entity(as[PostPutPatternRequest])) { (orgid, pattern, reqBody) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }
          var storedPatternPublic = false
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/patterns" + pattern + " service validation: " + v)
              var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break()
                  }
                }
              }
              if (invalidIndex < 0) PatternsTQ.getPublic(compositeId).result.asTry //getting public field from pattern
              else {
                val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({ xs =>
            logger.debug("PUT /orgs/"+orgid+"/patterns"+pattern+" checking public field of "+compositeId+": "+xs)
            xs match {
              case Success(patternPublic) => val public: Seq[Boolean] = patternPublic
                if(public.nonEmpty){
                  storedPatternPublic = public.head
                  if (public.head || reqBody.public.getOrElse(false)) {    // pattern is public so need to check orgType
                    OrgsTQ.getOrgType(orgid).result.asTry // should return a vector of Strings
                  } else { // pattern isn't public so skip orgType check
                    DBIO.successful(Vector("IBM")).asTry
                  }
                } else {
                  DBIO.failed(new auth.ResourceNotFoundException(ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }
          }).flatMap({
            case Success(orgTypes) =>
              logger.debug("PUT /orgs/" + orgid + "/patterns" + pattern + " checking orgType of " + orgid + ": " + orgTypes)
              if (orgTypes.head == "IBM") { // only patterns of orgType "IBM" can be public
                reqBody.toPatternRow(compositeId, orgid, owner).update.asTry
              } else {
                DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + " result: " + n)
              val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
              if (numUpdated > 0) {
                if (owner != "") AuthCache.putPatternOwner(compositeId, owner) // currently only users are allowed to update pattern resources, so owner should never be blank
                AuthCache.putPatternIsPublic(compositeId, reqBody.public.getOrElse(false))
                var publicField = false
                if (reqBody.public.isDefined) {
                  publicField = reqBody.public.getOrElse(false)
                }
                else {
                  publicField = storedPatternPublic
                }
                organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + " updated in changes table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: auth.ResourceNotFoundException) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.updated", compositeId, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{orgid}/patterns/{pattern} ===============================
  @PATCH
  @Path("{pattern}")
  @Operation(summary = "Updates 1 attribute of a pattern", description = "Updates one attribute of a pattern. This can only be called by the user that originally created this pattern resource.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",
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
        "enabled": true,
        "URL": "",
        "user": "",
        "password": "",
        "interval": 480,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      },
      "nodeHealth": {
        "missing_heartbeat_interval": 600,
        "check_agreement_status": 120
      }
    }
  ],
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
      "serviceOrgid": "myorg",
      "serviceUrl": "myservice",
      "serviceArch": "amd64",
      "serviceVersionRange": "x.y.z",
      "secrets": [
            {"<service-secret-name1>": "<vault-secret-name1>"},
            {"<service-secret-name2>": "<vault-secret-name2>"}
         ]
      }
   ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ],
  "clusterNamespace": "MyNamespace"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchPatternRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternPatchRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & patch & entity(as[PatchPatternRequest])) { (orgid, pattern, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/patterns/$pattern")
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid)
          var storedPatternPublic = false
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.pattern.attribute.specified")))
          else {
            val (valServiceIdActions, svcRefs) = if (attrName == "services") PatternsTQ.validateServiceIds(reqBody.services.get, List())
            else if (attrName == "userInput") PatternsTQ.validateServiceIds(List(), reqBody.userInput.get)
            else (DBIO.successful(Vector()), Vector())
            db.run(valServiceIdActions.asTry.flatMap({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/patterns" + pattern + " service validation: " + v)
                var invalidIndex: Int = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break()
                    }
                  }
                }
                if (invalidIndex < 0) PatternsTQ.getPublic(compositeId).result.asTry //getting public field from pattern
                else {
                  val errStr: String = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(patternPublic) =>
                logger.debug("PATCH /orgs/" + orgid + "/patterns" + pattern + " checking public field of " + compositeId + ": " + patternPublic)
                val public: Seq[Boolean] = patternPublic
                if (public.nonEmpty) {
                  val publicField: Boolean = reqBody.public.getOrElse(false)
                  storedPatternPublic = public.head
                  if ((public.head && publicField) || publicField) { // pattern is public so need to check owner
                    OrgsTQ.getOrgType(orgid).result.asTry
                  } else { // pattern isn't public so skip orgType check
                    DBIO.successful(Vector("IBM")).asTry
                  }
                } else {
                  DBIO.failed(new auth.ResourceNotFoundException(ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(patternOrg) =>
                logger.debug("PATCH /orgs/" + orgid + "/patterns" + pattern + " checking orgType of " + orgid + ": " + patternOrg)
                if (patternOrg.head == "IBM") { // only patterns of orgType "IBM" can be public
                  action.transactionally.asTry
                }
                else {
                  DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("PATCH /orgs/" + orgid + "/patterns/" + pattern + " result: " + v)
                val numUpdated: Int = v.asInstanceOf[Int] // v comes to us as type Any
                if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                  if (attrName == "public") AuthCache.putPatternIsPublic(compositeId, reqBody.public.getOrElse(false))
                  var publicField = false
                  if (reqBody.public.isDefined) {
                    publicField = reqBody.public.getOrElse(false)
                  }
                  else {
                    publicField = storedPatternPublic
                  }
                  organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERN, ResChangeOperation.MODIFIED).insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.attribute.not.update", attrName, compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/patterns/" + pattern + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.attribute.not.update", attrName, compositeId)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(_: auth.ResourceNotFoundException) =>
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId)))
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.not.updated", compositeId, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.updated", compositeId, t.getMessage)))
            })
          }
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/patterns/{pattern} ===============================
  @DELETE
  @Path("{pattern}")
  @Operation(summary = "Deletes a pattern", description = "Deletes a pattern. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternDeleteRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & delete) { (orgid, pattern) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/patterns/$pattern")
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field then do the delete
            logger.debug("DELETE /orgs/" + orgid + "/patterns/" + pattern + " public field is: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternsTQ.getPattern(compositeId).delete.transactionally.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/patterns/" + pattern + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removePatternOwner(compositeId)
              AuthCache.removePatternIsPublic(compositeId)
              organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERN, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/patterns/" + pattern + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.id.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.id.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/patterns/{pattern}/search ========================
  @POST
  @Path("{pattern}/search")
  @Operation(
    summary = "Returns matching nodes of a particular pattern",
    description = "Returns the matching nodes that are using this pattern and do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
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
  "arch": "arm",
  "nodeOrgids": [ "org1", "org2", "..." ],
  "secondsStale": 60,
  "serviceUrl": "myorg/mydomain.com.sdr"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPatternSearchRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostPatternSearchResponse])))
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternPostSearchRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "search") & post & entity(as[PostPatternSearchRequest])) { (orgid, pattern, reqBody) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TNode(OrgAndId(orgid,"*").toString), Access.READ) {agbot =>
      validateWithMsg(if(!(reqBody.secondsStale.isEmpty || !(reqBody.secondsStale.get < 0)) && !reqBody.serviceUrl.isEmpty) Some(ExchMsg.translate("bad.input")) else None) {
        complete({
          val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
//          logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search criteria: "+reqBody.toString)
          val searchSvcUrl: String = reqBody.serviceUrl   // this now is a composite value (org/url), but plain url is supported for backward compat
          val selectedServiceArch: Option[String] = reqBody.arch
          /*
            Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
            In english, the join gets: n.id, n.msgEndPoint, n.publicKey, a.serviceUrl, a.state
            The filters are: n is in the given list of node orgs, n.pattern==ourpattern, the node is not stale, there is an agreement for this node (the filter a.state=="" is applied later in our code below)
            Then we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
            Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
          */
          //val oldestTime = if (reqBody.secondsStale > 0) ApiTime.pastUTC(reqBody.secondsStale) else ApiTime.beginningUTC

          db.run(PatternsTQ.getServices(compositeId).result.flatMap({ list =>
//            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search getServices size: "+list.size)
//            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search: looking for '"+searchSvcUrl+"', searching getServices: "+list.toString())
            if (list.nonEmpty) {
              val services: Seq[PServices] = PatternsTQ.getServicesFromString(list.head)    // we should have found only 1 pattern services string, now parse it to get service list
              var found = false
              var svcArch = ""
              breakable { for ( svc <- services) {
                if (svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl || svc.serviceUrl == searchSvcUrl) {
                  found = true
                  svcArch = svc.serviceArch
                  break()
                }
              } }
              val archList = new ListBuffer[String]()
              val secondsStaleOpt: Option[Int] =
                if(reqBody.secondsStale.isDefined && reqBody.secondsStale.get.equals(0))
                  None
                else
                  reqBody.secondsStale
              
              for ( svc <- services) {
                if(svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl){
                  archList += svc.serviceArch
                }
              }
              archList += svcArch
              archList += ""
              archList += "*"
              val archSet: Set[String] = archList.toSet
              if (found) {
                /*     Build the node query
                 * 1 - if the caller specified a non-wildcard arch in the body, that trumps everything, so filter on that arch
                 * 2 - else if the caller or any service specified a blank/wildcard arch, then don't filter on arch at all
                 * 3 - else filter on the arches in the services
                 */
                val optArchSet: Option[Set[String]] = 
                  if(selectedServiceArch.isDefined && 
                     !(selectedServiceArch.contains("*") || 
                       selectedServiceArch.contains(""))) 
                    Some(Set(selectedServiceArch.get))
                  else if (((archSet("") || 
                             archSet("*")) && 
                            selectedServiceArch.isEmpty) || 
                           selectedServiceArch.contains("*") || 
                           selectedServiceArch.contains(""))
                    None
                  else
                    Some(archSet)
                
                NodesTQ
                  .filterOpt(optArchSet)((node, archs) => node.arch inSet(archs))
                  .filterOpt(secondsStaleOpt)((node, secondsStale) => !(node.lastHeartbeat < ApiTime.pastUTC(secondsStale)))
                  .filter(_.lastHeartbeat.isDefined)
                  .filter(_.orgid inSet(nodeOrgids))
                  .filter(_.pattern === compositeId)
                  .filter(_.publicKey =!= "")
                  .map(node => (node.id, node.nodeType, node.publicKey))
                .joinLeft(NodeAgreementsTQ
                            .filter(_.agrSvcUrl === searchSvcUrl)
                             .map(agreement => (agreement.agrSvcUrl, agreement.nodeId, agreement.state)))
                  .on((node, agreement) => node._1 === agreement._2)
                .filter ({
                  case (node, agreement) => 
                   (agreement.map(_._2).isEmpty ||
                    agreement.map(_._1).getOrElse("") === "" || 
                    agreement.map(_._3).getOrElse("") === "")
                })
                // .sortBy(r => (r._1._1.asc, r._2.getOrElse(("", "", ""))._1.asc.nullsFirst))
                .map(r => (r._1._1, r._1._2, r._1._3)).result.map(List[(String, String, String)]).asTry
              }
              //        else DBIO.failed(new Throwable("the serviceUrl '"+searchSvcUrl+"' specified in search body does not exist in pattern '"+compositePat+"'")).asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("service.not.in.pattern", searchSvcUrl, compositeId))).asTry
            }
            else DBIO.failed(new Throwable(ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          })).map({
            case Success(list) =>
//              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + "/search result size: " + list.size)
              if (list.nonEmpty) {
                (HttpCode.POST_OK, 
                 PostPatternSearchResponse(list
                                             .map(
                                               node => 
                                                 PatternNodeResponse(node._1, 
                                                                     node._2 match {
                                                                       case "" => NodeType.DEVICE.toString
                                                                       case _ => node._2
                                                                     }, 
                                                                     node._3)), 
                                           0))
              } 
              else {
                (HttpCode.NOT_FOUND, PostPatternSearchResponse(List[PatternNodeResponse](), 0))
              }
            case Failure(t: org.postgresql.util.PSQLException) =>
              ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("invalid.input.message", t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.input.message", t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/patterns/{pattern}/nodehealth ========================
  @POST
  @Path("{pattern}/nodehealth")
  @Operation(
    summary = "Returns agreement health of nodes of a particular pattern",
    description = "Returns the lastHeartbeat and agreement times for all nodes that are this pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "pattern",
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
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]",
  "nodeOrgids": ["org1", "org2", "..."]
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostNodeHealthRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value = """{
  "nodes": {
    "string": {
      "lastHeartbeat": "string",
      "agreements": {
        "string": {
          "lastUpdated": "string"
        }
      }
    }
  }
}
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[PostNodeHealthResponse])
          )
        )
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern")
  def patternNodeHealthRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "nodehealth") & post & entity(as[PostNodeHealthRequest])) { (orgid, pattern, reqBody) =>
    exchAuth(TNode(OrgAndId(orgid,"*").toString), Access.READ) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val compositePat: String = OrgAndId(orgid, pattern).toString
          val nodeOrgids: Set[String] = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
          logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth criteria: "+reqBody.toString)
          /*
            Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
            The filter is: n.pattern==ourpattern && n.lastHeartbeat>=lastTime
            Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
          */
          val lastTime: String = if (reqBody.lastTime != "") reqBody.lastTime else ApiTime.beginningUTC
          val q = for {
            (n, a) <- NodesTQ.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ on (_.id === _.nodeId)
          } yield (n.id, n.lastHeartbeat, a.map(_.agId), a.map(_.lastUpdated))

          db.run(q.result).map({ list =>
            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result size: "+list.size)
            //logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth result: "+list.toString)
            if (list.nonEmpty) (HttpCode.POST_OK, PostNodeHealthResponse(RouteUtils.buildNodeHealthHash(list)))
            else (HttpCode.NOT_FOUND, PostNodeHealthResponse(Map[String,NodeHealthHashElement]()))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/patterns/{pattern}/keys ================================ */
  @GET
  @Path("{pattern}/keys")
  @Operation(summary = "Returns all keys/certs for this pattern", description = "Returns all the signing public keys/certs for this pattern. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[List[String]])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def patternGetKeysRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys") & get) { (orgid, pattern) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId),Access.READ) { _ =>
      complete({
        db.run(PatternKeysTQ.getKeys(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/patterns/$pattern/keys result size: ${list.size}")
          val code: StatusCode = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.map(_.keyId))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ================================ */
  @GET
  @Path("{pattern}/keys/{keyid}")
  @Operation(summary = "Returns a key/cert for this pattern", description = "Returns the signing public key/cert with the specified keyid for this pattern. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "Signing public key/certificate identifier.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def patternGetKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & get) { (orgid, pattern, keyId) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId),Access.READ) { _ =>
      complete({
        db.run(PatternKeysTQ.getKey(compositeId, keyId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" result: "+list.size)
          // Note: both responses must be the same content type or that doesn't get set correctly
          if (list.nonEmpty) HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, list.head.key))
          else HttpResponse(status = HttpCode.NOT_FOUND, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))
          // this doesn't seem to set the content type away from application/json
          //if (list.nonEmpty) (HttpCode.OK, List(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)), list.head.key)
          //else (HttpCode.NOT_FOUND, List(`Content-Type`(ContentTypes.`text/plain(UTF-8)`)), "")
          //if (list.nonEmpty) (HttpCode.OK, list.head.key)
          //else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("key.not.found", keyId)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ===============================
  @PUT
  @Path("{pattern}/keys/{keyid}")
  @Operation(summary = "Adds/updates a key/cert for the pattern", description = "Adds a new signing public key/cert, or updates an existing key/cert, for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "ID of the pattern to be updated."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key to be added/updated.")),
    requestBody = new RequestBody(description = "Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "key": "string"
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PutPatternKeyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def patternPutKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & put) { (orgid, pattern, keyId) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId),Access.WRITE) { _ =>
      extractRawBodyAsStr { reqBodyAsStr =>
        val reqBody: PutPatternKeyRequest = PutPatternKeyRequest(reqBodyAsStr)
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            db.run(reqBody.toPatternKeyRow(compositeId, keyId).upsert.asTry.flatMap({
              case Success(v) =>
                // Get the value of the public field
                logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " result: " + v)
                PatternsTQ.getPublic(compositeId).result.asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(public) =>
                // Add the resource to the resourcechanges table
                logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " public field: " + public)
                if (public.nonEmpty) {
                  val publicField: Boolean = public.head
                  organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, publicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
              case Failure(t: org.postgresql.util.PSQLException) =>
                if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
              case Failure(t) =>
                if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
            })
          }) // end of complete
        } // end of validateWithMsg
      } // end of extractRawBodyAsStr
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/patterns/{pattern}/keys ===============================
  @DELETE
  @Path("{pattern}/keys")
  @Operation(summary = "Deletes all keys of a pattern", description = "Deletes all of the current keys/certs for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def patternDeleteKeysRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys") & delete) { (orgid, pattern) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the public field before doing delete
            logger.debug("DELETE /patterns/" + pattern + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternKeysTQ.getKeys(compositeId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /patterns/" + pattern + "/keys result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.pattern.keys.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /patterns/" + pattern + "/keys result: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.keys.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.keys.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.keys.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ===============================
  @DELETE
  @Path("{pattern}/keys/{keyid}")
  @Operation(summary = "Deletes a key of a pattern", description = "Deletes a key/cert for this pattern. This can only be run by the pattern owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "deployment pattern/key")
  def patternDeleteKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & delete) { (orgid, pattern, keyId) =>
    val compositeId: String = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(PatternsTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the public field before doing delete
            logger.debug("DELETE /patterns/" + pattern + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              PatternKeysTQ.getKey(compositeId, keyId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /patterns/" + pattern + "/keys/" + keyId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              organization.ResourceChange(0L, orgid, pattern, ResChangeCategory.PATTERN, storedPublicField, ResChangeResource.PATTERNKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.key.not.found", keyId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /patterns/" + pattern + "/keys result: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.key.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("pattern.key.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.key.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

}
