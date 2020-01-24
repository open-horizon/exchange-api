/** Services routes for all of the /orgs/{orgid}/patterns api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._

import scala.concurrent.ExecutionContext
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations._

import com.horizon.exchangeapi.tables._
import org.json4s._

import scala.collection.immutable._
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

import org.json4s.jackson.Serialization.write
import slick.jdbc.PostgresProfile.api._

//====== These are the input and output structures for /orgs/{orgid}/patterns routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/patterns */
final case class GetPatternsResponse(patterns: Map[String,Pattern], lastIndex: Int)
final case class GetPatternAttributeResponse(attribute: String, value: String)

/** Input for pattern-based search for nodes to make agreements with. */
final case class PostPatternSearchRequest(serviceUrl: String, nodeOrgids: Option[List[String]], secondsStale: Int, startIndex: Int, numEntries: Int, arch: Option[String]) {
  require(serviceUrl!=null)
  def getAnyProblem: Option[String] = None
}

object PatternUtils {
  def validatePatternServices(services: List[PServices]): Option[String] = {
    // Check that it is signed and check the version syntax
    for (s <- services) {
      if (s.serviceVersions.isEmpty) return Some(ExchMsg.translate("no.version.specified.for.service", s.serviceOrgid, s.serviceUrl, s.serviceArch))
      for (sv <- s.serviceVersions) {
        if (!Version(sv.version).isValid) return Some(ExchMsg.translate("version.not.valid.format", sv.version))
        if (sv.deployment_overrides.getOrElse("") != "" && sv.deployment_overrides_signature.getOrElse("") == "") {
          return Some(ExchMsg.translate("pattern.definition.not.signed"))
        }
      }
    }
    return None
  }
}

/** Input format for POST/PUT /orgs/{orgid}/patterns/<pattern-id> */
final case class PostPutPatternRequest(label: String, description: Option[String], public: Option[Boolean], services: List[PServices], userInput: Option[List[OneUserInputService]], agreementProtocols: Option[List[Map[String,String]]]) {
  require(label!=null && services!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    if(services.isEmpty) return Some(ExchMsg.translate("no.services.defined.in.pattern"))
    PatternUtils.validatePatternServices(services)
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: (DBIO[Vector[Int]], Vector[ServiceRef2]) = { PatternsTQ.validateServiceIds(services, userInput.getOrElse(List())) }

  // Note: write() handles correctly the case where the optional fields are None.
  def toPatternRow(pattern: String, orgid: String, owner: String): PatternRow = {
    // The nodeHealth field is optional, so fill in a default in each element of services if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
    val hbDefault = ExchConfig.getInt("api.defaults.pattern.missing_heartbeat_interval")
    val agrChkDefault = ExchConfig.getInt("api.defaults.pattern.check_agreement_status")
    val services2 = if (services.nonEmpty) {
      services.map({ s =>
        val nodeHealth2 = s.nodeHealth.orElse(Some(Map("missing_heartbeat_interval" -> hbDefault, "check_agreement_status" -> agrChkDefault)))
        PServices(s.serviceUrl, s.serviceOrgid, s.serviceArch, s.agreementLess, s.serviceVersions, s.dataVerification, nodeHealth2)
      })
    } else {
      services
    }
    val agreementProtocols2 = agreementProtocols.orElse(Some(List(Map("name" -> "Basic"))))
    PatternRow(pattern, orgid, owner, label, description.getOrElse(label), public.getOrElse(false), write(services2), write(userInput), write(agreementProtocols2), ApiTime.nowUTC)
  }
}

final case class PatchPatternRequest(label: Option[String], description: Option[String], public: Option[Boolean], services: Option[List[PServices]], userInput: Option[List[OneUserInputService]], agreementProtocols: Option[List[Map[String,String]]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ if (services.isDefined) PatternUtils.validatePatternServices(services.get)
    else None
  }

  /** Returns a tuple of the db action to update parts of the pattern, and the attribute name being updated. */
  def getDbUpdate(pattern: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this pattern
    label match { case Some(lab) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.label,d.lastUpdated)).update((pattern, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.description,d.lastUpdated)).update((pattern, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.public,d.lastUpdated)).update((pattern, pub, lastUpdated)), "public"); case _ => ; }
    services match { case Some(svc) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.services,d.lastUpdated)).update((pattern, write(svc), lastUpdated)), "services"); case _ => ; }
    userInput match { case Some(input) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.userInput,d.lastUpdated)).update((pattern, write(input), lastUpdated)), "userInput"); case _ => ; }
    agreementProtocols match { case Some(ap) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.agreementProtocols,d.lastUpdated)).update((pattern, write(ap), lastUpdated)), "agreementProtocols"); case _ => ; }
    return (null, null)
  }

}


/** Input format for PUT /orgs/{orgid}/patterns/{pattern}/keys/<key-id> */
final case class PutPatternKeyRequest(key: String) {
  require(key!=null)
  def toPatternKey = PatternKey(key, ApiTime.nowUTC)
  def toPatternKeyRow(patternId: String, keyId: String) = PatternKeyRow(keyId, patternId, key, ApiTime.nowUTC)
  def getAnyProblem: Option[String] = None
}


/** Implementation for all of the /orgs/{orgid}/patterns routes */
@Path("/v1/orgs/{orgid}/patterns")
trait PatternsRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def patternsRoutes: Route = patternsGetRoute ~ patternGetRoute ~ patternPostRoute ~ patternPuttRoute ~ patternPatchRoute ~ patternDeleteRoute ~ patternPostSearchRoute ~ patternNodeHealthRoute ~ patternGetKeysRoute ~ patternGetKeyRoute ~ patternPutKeyRoute ~ patternDeleteKeysRoute ~ patternDeleteKeyRoute

  /* ====== GET /orgs/{orgid}/patterns ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this public setting"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this description (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetPatternsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternsGetRoute: Route = (path("orgs" / Segment / "patterns") & get & parameter(('idfilter.?, 'owner.?, 'public.?, 'label.?, 'description.?))) { (orgid, idfilter, owner, public, label, description) =>
    exchAuth(TPattern(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
        complete({
          //var q = PatternsTQ.rows.subquery
          var q = PatternsTQ.getAllPatterns(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
          description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

          db.run(q.result).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/patterns result size: "+list.size)
            val patterns = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
            val code = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
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
        content = Array(new Content(schema = new Schema(implementation = classOf[GetPatternsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternGetRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & get & parameter(('attribute.?))) { (orgid, pattern, attribute) =>
    val compositeId = OrgAndId(orgid,pattern).toString
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
              val patterns = list.map(e => e.pattern -> e.toPattern).toMap
              val code = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetPatternsResponse(patterns, 0))
            })
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/patterns/{pattern} ===============================
  @POST
  @Path("{pattern}")
  @Operation(summary = "Adds a pattern", description = "Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = """
```
{
  "label": "name of the edge pattern",     // this will be displayed in the node registration UI
  "description": "descriptive text",
  "public": false,       // typically patterns are not appropriate to share across orgs because they contain policy choices
  // The services that should be deployed to the edge for this pattern. (The services must exist before creating this pattern.)
  "services": [
    {
      "serviceUrl": "mydomain.com.weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      "agreementLess": false,  // only set to true if the same svc is both top level and required by another svc
      // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",     // filled in by the Horizon signing process
          "priority": {      // can be omitted
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
          "upgradePolicy": {      // can be omitted
            "lifecycle": "immediate",
            "time": "01:00AM"     // reserved for future use
          }
        }
      ],
      // Fill in this section if the Horizon agbot should run a REST API of the cloud data ingest service to confirm the service is sending data.
      // If not using this, the dataVerification field can be set to {} or omitted completely.
      "dataVerification": {      // can be omitted
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
      // If not using agbot node health check, this field can be set to {} or omitted completely.
      "nodeHealth": {      // can be omitted
        "missing_heartbeat_interval": 600,      // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
        "check_agreement_status": 120        // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
      }
    }
  ],
  // Override or set user input variables that are defined in the services used by this pattern.
  "userInput": [
    {
      "serviceOrgid": "IBM",
      "serviceUrl": "ibm.cpu2msghub",
      "serviceArch": "",        // omit or leave blank to mean all architectures
      "serviceVersionRange": "[0.0.0,INFINITY)",   // or omit to mean all versions
      "inputs": [
        {
          "name": "foo",
          "value": "bar"
        }
      ]
    }
  ],
  // The Horizon agreement protocol(s) to use. "Basic" means make agreements w/o a blockchain. "Citizen Scientist" means use ethereum to record the agreement.
  "agreementProtocols": [      // can be omitted
    {
      "name": "Basic"
    }
  ]
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostPutPatternRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternPostRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & post & entity(as[PostPutPatternRequest])) { (orgid, pattern, reqBody) =>
    val compositeId = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
          // Get optional agbots that should be updated with this new pattern
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " service validation: " + v)
              var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break
                  }
                }
              }
              if (invalidIndex < 0) OrgsTQ.getAttribute(orgid, "orgType").result.asTry //getting orgType from orgid
              else {
                val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(orgName) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " checking public field and orgType of " + compositeId + ": " + orgName)
              val orgType = orgName
              val publicField = reqBody.public.getOrElse(false)
              if ((publicField && orgType.head == "IBM") || !publicField) { // pattern is public and owner is IBM so ok, or pattern isn't public at all so ok
                PatternsTQ.getNumOwned(owner).result.asTry
              } else DBIO.failed(new BadInputException(ExchMsg.translate("only.ibm.patterns.can.be.public"))).asTry
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/patterns" + pattern + " num owned by " + owner + ": " + num)
              val numOwned = num
              val maxPatterns = ExchConfig.getInt("api.limits.maxPatterns")
              if (maxPatterns == 0 || numOwned <= maxPatterns) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.toPatternRow(compositeId, orgid, owner).insert.asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.limit.of.max.patterns", maxPatterns))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + " result: " + v)
              val publicField = reqBody.public.getOrElse(false)
              val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", publicField.toString, "pattern", ResourceChangeConfig.CREATED, ApiTime.nowUTC)
              patternChange.insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + " updated in changes table: " + v)
              if (owner != "") AuthCache.putPatternOwner(compositeId, owner) // currently only users are allowed to update pattern resources, so owner should never be blank
              AuthCache.putPatternIsPublic(compositeId, reqBody.public.getOrElse(false))
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("pattern.created", compositeId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t) =>
              if (t.getMessage.contains("duplicate key value violates unique constraint")) { // "duplicate key value violates unique constraint" comes from postgres
                (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("pattern.already.exists", compositeId, t.getMessage)))
              } else {
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("pattern.not.created", compositeId, t.getMessage)))
              }
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
    requestBody = new RequestBody(description = "See details in the POST route.", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostPutPatternRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource created - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternPuttRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & put & entity(as[PostPutPatternRequest])) { (orgid, pattern, reqBody) =>
    val compositeId = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
          var storedPatternPublic = false
          val (valServiceIdActions, svcRefs) = reqBody.validateServiceIds  // to check that the services referenced exist
          db.run(valServiceIdActions.asTry.flatMap({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/patterns" + pattern + " service validation: " + v)
              var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
              breakable {
                for ((len, index) <- v.zipWithIndex) {
                  if (len <= 0) {
                    invalidIndex = index
                    break
                  }
                }
              }
              if (invalidIndex < 0) PatternsTQ.getPublic(compositeId).result.asTry //getting public field from pattern
              else {
                val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({ xs =>
            logger.debug("PUT /orgs/"+orgid+"/patterns"+pattern+" checking public field of "+compositeId+": "+xs)
            xs match {
              case Success(patternPublic) => val public = patternPublic
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
              val numUpdated = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
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
                val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", publicField.toString, "pattern", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
                patternChange.insert.asTry
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
    requestBody = new RequestBody(description = "Specify only **one** of the attributes (see list of attributes in the POST route)", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PatchPatternRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "resource updated - response body:",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternPatchRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & patch & entity(as[PatchPatternRequest])) { (orgid, pattern, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/patterns/$pattern")
    val compositeId = OrgAndId(orgid, pattern).toString
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
                var invalidIndex = -1 // v is a vector of Int (the length of each service query). If any are zero we should error out.
                breakable {
                  for ((len, index) <- v.zipWithIndex) {
                    if (len <= 0) {
                      invalidIndex = index
                      break
                    }
                  }
                }
                if (invalidIndex < 0) PatternsTQ.getPublic(compositeId).result.asTry //getting public field from pattern
                else {
                  val errStr = if (invalidIndex < svcRefs.length) ExchMsg.translate("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
                  else ExchMsg.translate("service.not.in.exchange.index", Nth(invalidIndex + 1))
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(patternPublic) =>
                logger.debug("PATCH /orgs/" + orgid + "/patterns" + pattern + " checking public field of " + compositeId + ": " + patternPublic)
                val public = patternPublic
                if (public.nonEmpty) {
                  val publicField = reqBody.public.getOrElse(false)
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
                val numUpdated = v.asInstanceOf[Int] // v comes to us as type Any
                if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                  if (attrName == "public") AuthCache.putPatternIsPublic(compositeId, reqBody.public.getOrElse(false))
                  var publicField = false
                  if (reqBody.public.isDefined) {
                    publicField = reqBody.public.getOrElse(false)
                  }
                  else {
                    publicField = storedPatternPublic
                  }
                  val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", publicField.toString, "pattern", ResourceChangeConfig.MODIFIED, ApiTime.nowUTC)
                  patternChange.insert.asTry
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
  def patternDeleteRoute: Route = (path("orgs" / Segment / "patterns" / Segment) & delete) { (orgid, pattern) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/patterns/$pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
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
              val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", storedPublicField.toString, "pattern", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              patternChange.insert.asTry
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
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.id.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // ======== POST /org/{orgid}/patterns/{pattern}/search ========================
  @POST
  @Path("{pattern}/search")
  @Operation(summary = "Returns matching nodes of a particular pattern", description = "Returns the matching nodes that are using this pattern and do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = """
```
{
  "serviceUrl": "myorg/mydomain.com.sdr",   // The service that the node does not have an agreement with yet. Composite svc url (org/svc)
  "nodeOrgids": [ "org1", "org2", "..." ],   // if not specified, defaults to the same org the pattern is in
  "secondsStale": 60,     // max number of seconds since the exchange has heard from the node, 0 if you do not care
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0,    // ignored right now
  "arch": "arm"     // optional
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostPatternSearchRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[PostPatternSearchResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternPostSearchRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "search") & post & entity(as[PostPatternSearchRequest])) { (orgid, pattern, reqBody) =>
    val compositeId = OrgAndId(orgid, pattern).toString
    exchAuth(TNode(OrgAndId(orgid,"*").toString), Access.READ) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val nodeOrgids = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
          logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search criteria: "+reqBody.toString)
          val searchSvcUrl = reqBody.serviceUrl   // this now is a composite value (org/url), but plain url is supported for backward compat
          val selectedServiceArch = reqBody.arch
          /*
            Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
            In english, the join gets: n.id, n.msgEndPoint, n.publicKey, a.serviceUrl, a.state
            The filters are: n is in the given list of node orgs, n.pattern==ourpattern, the node is not stale, there is an agreement for this node (the filter a.state=="" is applied later in our code below)
            Then we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
            Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
          */
          val oldestTime = if (reqBody.secondsStale > 0) ApiTime.pastUTC(reqBody.secondsStale) else ApiTime.beginningUTC

          def isEqualUrl(agrSvcUrl: String, searchSvcUrl: String): Boolean = {
            if (agrSvcUrl == searchSvcUrl) return true    // this is the relevant check when both agbot and agent are recent enough to use composite urls (org/org)
            // Assume searchSvcUrl is the new composite format (because the agbot is at least as high version as the agent) and strip off the org
            val reg = """^\S+?/(\S+)$""".r
            searchSvcUrl match {
              case reg(url) => return agrSvcUrl == url
              case _ => return false    // searchSvcUrl was not composite, so the urls are not equal
            }
          }

          db.run(PatternsTQ.getServices(compositeId).result.flatMap({ list =>
            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search getServices size: "+list.size)
            logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search: looking for '"+searchSvcUrl+"', searching getServices: "+list.toString())
            if (list.nonEmpty) {
              val services = PatternsTQ.getServicesFromString(list.head)    // we should have found only 1 pattern services string, now parse it to get service list
              var found = false
              var svcArch = ""
              breakable { for ( svc <- services) {
                if (svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl || svc.serviceUrl == searchSvcUrl) {
                  found = true
                  svcArch = svc.serviceArch
                  break
                }
              } }
              val archList = new ListBuffer[String]()
              for ( svc <- services) {
                if(svc.serviceOrgid+"/"+svc.serviceUrl == searchSvcUrl){
                  archList += svc.serviceArch
                }
              }
              archList += svcArch
              archList += ""
              archList += "*"
              val archSet = archList.toSet
              if (found) {
                /*
                1 - if the caller specified a non-wildcard arch in the body, that trumps everything, so filter on that arch
                2 - else if the caller or any service specified a blank/wildcard arch, then don't filter on arch at all
                3 - else filter on the arches in the services
                 */
                //if(selectedServiceArch.isDefined && !(selectedServiceArch.equals(Some("*")) || selectedServiceArch.equals(Some("")))){
                if(selectedServiceArch.isDefined && !(selectedServiceArch.contains("*") || selectedServiceArch.contains(""))){
                  val nodeQuery =
                    for {
                      (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositeId).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime).filter(_.arch like selectedServiceArch) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
                    } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
                  nodeQuery.result.asTry
                //} else if (((archSet("") || archSet("*")) && selectedServiceArch.isEmpty) || selectedServiceArch.equals(Some("*")) || selectedServiceArch.equals(Some(""))){
                } else if (((archSet("") || archSet("*")) && selectedServiceArch.isEmpty) || selectedServiceArch.contains("*") || selectedServiceArch.contains("")){
                  val nodeQuery =
                    for {
                      (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositeId).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
                    } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
                  nodeQuery.result.asTry
                } else {
                  val nodeQuery =
                    for {
                      (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositeId).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime).filter(_.arch inSet(archSet)) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
                    } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
                  nodeQuery.result.asTry
                }
              }
              //        else DBIO.failed(new Throwable("the serviceUrl '"+searchSvcUrl+"' specified in search body does not exist in pattern '"+compositePat+"'")).asTry
              else DBIO.failed(new Throwable(ExchMsg.translate("service.not.in.pattern", searchSvcUrl, compositeId))).asTry

            }
            else DBIO.failed(new Throwable(ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
          })).map({
            case Success(list) =>
              logger.debug("POST /orgs/" + orgid + "/patterns/" + pattern + "/search result size: " + list.size)
              if (list.nonEmpty) {
                // Go thru the rows and build a hash of the nodes that do NOT have an agreement for our service
                val nodeHash = new MutableHashMap[String, PatternSearchHashElement] // key is node id, value noAgreementYet which is true if so far we haven't hit an agreement for our service for this node
                for ((nodeid, msgEndPoint, publicKey, agrSvcUrlOpt, stateOpt) <- list) {
                  //logger.debug("nodeid: "+nodeid+", agrSvcUrlOpt: "+agrSvcUrlOpt.getOrElse("")+", searchSvcUrl: "+searchSvcUrl+", stateOpt: "+stateOpt.getOrElse(""))
                  nodeHash.get(nodeid) match {
                    case Some(_) => if (isEqualUrl(agrSvcUrlOpt.getOrElse(""), searchSvcUrl) && stateOpt.getOrElse("") != "") {
                      /*logger.debug("setting to false");*/ nodeHash.put(nodeid, PatternSearchHashElement(msgEndPoint, publicKey, noAgreementYet = false))
                    } // this is no longer a candidate
                    case None => val noAgr = if (isEqualUrl(agrSvcUrlOpt.getOrElse(""), searchSvcUrl) && stateOpt.getOrElse("") != "") false else true
                      nodeHash.put(nodeid, PatternSearchHashElement(msgEndPoint, publicKey, noAgr)) // this node nodeid not in the hash yet, add it
                  }
                }
                // Convert our hash to the list response of the rest api
                //val respList = list.map( x => PatternNodeResponse(x._1, x._2, x._3)).toList
                val respList = new ListBuffer[PatternNodeResponse]
                for ((k, v) <- nodeHash) if (v.noAgreementYet) respList += PatternNodeResponse(k, v.msgEndPoint, v.publicKey)
                val code = if (respList.nonEmpty) HttpCode.POST_OK else HttpCode.NOT_FOUND
                (code, PostPatternSearchResponse(respList.toList, 0))
              } else {
                (HttpCode.NOT_FOUND, PostPatternSearchResponse(List[PatternNodeResponse](), 0))
              }
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
  @Operation(summary = "Returns agreement health of nodes of a particular pattern", description = "Returns the lastHeartbeat and agreement times for all nodes that are this pattern and have changed since the specified lastTime. Can be run by a user or agbot (but not a node).",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    requestBody = new RequestBody(description = """
```
{
  "lastTime": "2017-09-28T13:51:36.629Z[UTC]",   // only return nodes that have changed since this time, empty string returns all
  "nodeOrgids": [ "org1", "org2", "..." ]   // if not specified, defaults to the same org the pattern is in
}
```""", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PostNodeHealthRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[PostNodeHealthResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternNodeHealthRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "nodehealth") & post & entity(as[PostNodeHealthRequest])) { (orgid, pattern, reqBody) =>
    exchAuth(TNode(OrgAndId(orgid,"*").toString), Access.READ) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val compositePat = OrgAndId(orgid,pattern).toString
          val nodeOrgids = reqBody.nodeOrgids.getOrElse(List(orgid)).toSet
          logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/nodehealth criteria: "+reqBody.toString)
          /*
            Join nodes and agreements and return: n.id, n.lastHeartbeat, a.id, a.lastUpdated.
            The filter is: n.pattern==ourpattern && n.lastHeartbeat>=lastTime
            Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
          */
          val lastTime = if (reqBody.lastTime != "") reqBody.lastTime else ApiTime.beginningUTC
          val q = for {
            (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.lastHeartbeat >= lastTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
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
  @Path("{pattern}}/keys")
  @Operation(summary = "Returns all keys/certs for this pattern", description = "Returns all the signing public keys/certs for this pattern. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[List[String]])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternGetKeysRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys") & get) { (orgid, pattern) =>
    val compositeId = OrgAndId(orgid,pattern).toString
    exchAuth(TPattern(compositeId),Access.READ) { _ =>
      complete({
        db.run(PatternKeysTQ.getKeys(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/patterns/$pattern/keys result size: ${list.size}")
          val code = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.map(_.keyId))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ================================ */
  @GET
  @Path("{pattern}}/keys/{keyid}")
  @Operation(summary = "Returns a key/cert for this pattern", description = "Returns the signing public key/cert with the specified keyid for this pattern. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the pattern.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "pattern", in = ParameterIn.PATH, description = "Pattern name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[String])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternGetKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & get) { (orgid, pattern, keyId) =>
    val compositeId = OrgAndId(orgid,pattern).toString
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
      new Parameter(name = "id", in = ParameterIn.PATH, description = "ID of the pattern to be updated."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key to be added/updated.")),
    requestBody = new RequestBody(description = "Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'.", required = true, content = Array(new Content(schema = new Schema(implementation = classOf[PutPatternKeyRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def patternPutKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & put) { (orgid, pattern, keyId) =>
    val compositeId = OrgAndId(orgid, pattern).toString
    exchAuth(TPattern(compositeId),Access.WRITE) { _ =>
      extractRawBodyAsStr { reqBodyAsStr =>
        val reqBody = PutPatternKeyRequest(reqBodyAsStr)
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
                  val publicField = public.head
                  val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", publicField.toString, "patternkeys", ResourceChangeConfig.CREATEDMODIFIED, ApiTime.nowUTC)
                  patternChange.insert.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("pattern.id.not.found", compositeId))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PUT /orgs/" + orgid + "/patterns/" + pattern + "/keys/" + keyId + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
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
  def patternDeleteKeysRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys") & delete) { (orgid, pattern) =>
    val compositeId = OrgAndId(orgid,pattern).toString
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
              val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", storedPublicField.toString, "patternkeys", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              patternChange.insert.asTry
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
  def patternDeleteKeyRoute: Route = (path("orgs" / Segment / "patterns" / Segment / "keys" / Segment) & delete) { (orgid, pattern, keyId) =>
    val compositeId = OrgAndId(orgid,pattern).toString
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
              val patternChange = ResourceChangeRow(0, orgid, pattern, "pattern", storedPublicField.toString, "patternkeys", ResourceChangeConfig.DELETED, ApiTime.nowUTC)
              patternChange.insert.asTry
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
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("pattern.key.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

}