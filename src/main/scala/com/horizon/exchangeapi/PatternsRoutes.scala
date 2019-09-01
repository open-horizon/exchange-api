/** Services routes for all of the /orgs/{orgid}/patterns api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
//import java.net._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/patterns routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/patterns */
case class GetPatternsResponse(patterns: Map[String,Pattern], lastIndex: Int)
case class GetPatternAttributeResponse(attribute: String, value: String)

/** Input for pattern-based search for nodes to make agreements with. */
case class PostPatternSearchRequest(serviceUrl: String, nodeOrgids: Option[List[String]], secondsStale: Int, startIndex: Int, numEntries: Int) {
  def validate() = { }
}

/** Input format for POST/PUT /orgs/{orgid}/patterns/<pattern-id> */
case class PostPutPatternRequest(label: String, description: Option[String], public: Option[Boolean], services: List[PServices], userInput: Option[List[OneUserInputService]], agreementProtocols: Option[List[Map[String,String]]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(): Unit = {
    // Check that it is signed and check the version syntax
    for (s <- services) {
      if (s.serviceVersions.isEmpty) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("no.version.specified.for.service", s.serviceOrgid, s.serviceUrl, s.serviceArch)))
      for (sv <- s.serviceVersions) {
        if (!Version(sv.version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("not.a.valid.version.format", sv.version)))
        if (sv.deployment_overrides.getOrElse("") != "" && sv.deployment_overrides_signature.getOrElse("") == "") {
          halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.definition.not.signed")))
        }
      }
    }
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

case class PatchPatternRequest(label: Option[String], description: Option[String], public: Option[Boolean], services: Option[List[PServices]], userInput: Option[List[OneUserInputService]], agreementProtocols: Option[List[Map[String,String]]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the pattern, and the attribute name being updated. */
  def getDbUpdate(pattern: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
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
case class PutPatternKeyRequest(key: String) {
  def toPatternKey = PatternKey(key, ApiTime.nowUTC)
  def toPatternKeyRow(patternId: String, keyId: String) = PatternKeyRow(keyId, patternId, key, ApiTime.nowUTC)
  def validate(keyId: String) = {
    //if (keyId != formId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the key id should be in the form keyOrgid_key"))
  }
}



/** Implementation for all of the /orgs/{orgid}/patterns routes */
trait PatternRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/patterns ================================ */
  val getPatterns =
    (apiOperation[GetPatternsResponse]("getPatterns")
      summary("Returns all patterns")
      description("""Returns all pattern definitions in this organization. Can be run by any user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include patterns with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include patterns with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("public", DataType.String, Option[String]("Filter results to only include patterns with this public setting"), paramType=ParamType.Query, required=false),
        Parameter("label", DataType.String, Option[String]("Filter results to only include patterns with this label (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("description", DataType.String, Option[String]("Filter results to only include patterns with this description (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/patterns", operation(getPatterns)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TPattern(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    //var q = PatternsTQ.rows.subquery
    var q = PatternsTQ.getAllPatterns(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("public").foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
    params.get("label").foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
    params.get("description").foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/patterns result size: "+list.size)
      val patterns = new MutableHashMap[String,Pattern]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || a.public || ident.isSuperUser || ident.isMultiTenantAgbot) patterns.put(a.pattern, a.toPattern)
      if (patterns.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetPatternsResponse(patterns.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/patterns/{pattern} ================================ */
  val getOnePattern =
    (apiOperation[GetPatternsResponse]("getOnePattern")
      summary("Returns a pattern")
      description("""Returns the pattern with the specified id in the exchange DB. Can be run by a user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire pattern resource will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/patterns/:pattern", operation(getOnePattern)) ({
    val orgid = params("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    authenticate().authorizeTo(TPattern(pattern),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the pattern
        val q = PatternsTQ.getAttribute(pattern, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.attr.not.in.pattern", attribute)))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/patterns/"+barePattern+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetPatternAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("not.found"))
          }
        })

      case None => ;  // Return the whole pattern resource
        db.run(PatternsTQ.getPattern(pattern).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/patterns/"+barePattern+" result: "+list.size)
          //logger.trace("GET /orgs/"+orgid+"/patterns/"+barePattern+" result: "+list.toString)
          val patterns = new MutableHashMap[String,Pattern]
          if (list.nonEmpty) for (a <- list) patterns.put(a.pattern, a.toPattern)
          if (patterns.nonEmpty) resp.setStatus(HttpCode.OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetPatternsResponse(patterns.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/patterns/{pattern} ===============================
  val postPatterns =
    (apiOperation[ApiResponse]("postPatterns")
      summary "Adds a pattern"
      description """Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
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
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        //Parameter("updateagbot", DataType.String, Option[String]("An agbot resource id (org/agbotid) that should be updated to serve this pattern. Can be specified multiple times."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutPatternRequest],
        Option[String]("Pattern object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postPatterns2 = (apiOperation[PostPutPatternRequest]("postPatterns2") summary("a") description("a"))  // for some bizarre reason, the PostPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/patterns/:pattern", operation(postPatterns)) ({
    val orgid = params("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    val ident = authenticate().authorizeTo(TPattern(OrgAndId(orgid,"").toString),Access.CREATE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    // Get optional agbots that should be updated with this new pattern
    //val agbotParams = multiParams("updateagbot")
    val resp = response
    val (valServiceIdActions, svcRefs) = patternReq.validateServiceIds  // to check that the services referenced exist
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) OrgsTQ.getAttribute(orgid, "orgType").result.asTry //getting orgType from orgid
          else {
            val errStr = if (invalidIndex < svcRefs.length) ExchangeMessage.translateMessage("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
            else ExchangeMessage.translateMessage("service.not.in.exchange.index", Nth(invalidIndex+1))
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns"+barePattern+" checking public field and orgType of "+pattern+": "+xs)
      xs match {
        case Success(orgName) => val orgType = orgName
          val publicField = patternReq.public.getOrElse(false)
          if ((publicField && orgType.head == "IBM") || !publicField) {    // pattern is public and owner is IBM so ok, or pattern isn't public at all so ok
            PatternsTQ.getNumOwned(owner).result.asTry
          } else DBIO.failed(new BadInputException(HttpCode.BAD_INPUT, ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("only.ibm.patterns.can.be.public"))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns"+barePattern+" num owned by "+owner+": "+xs)
      xs match {
        case Success(num) => val numOwned = num
          val maxPatterns = ExchConfig.getInt("api.limits.maxPatterns")
          if (maxPatterns == 0 || numOwned <= maxPatterns) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
            patternReq.toPatternRow(pattern, orgid, owner).insert.asTry
          }
          else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("over.limit.of.max.patterns", maxPatterns))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.patternsOwner.putOne(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
          AuthCache.patterns.putIsPublic(pattern, patternReq.public.getOrElse(false))
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.created", pattern))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("pattern.not.created", pattern, t.getMessage))
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {   // "duplicate key value violates unique constraint" comes from postgres
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, ExchangeMessage.translateMessage("pattern.already.exists", pattern, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.not.created", pattern, t.getMessage))
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/patterns/{pattern} ===============================
  val putPatterns =
    (apiOperation[ApiResponse]("putPatterns")
      summary "Updates a pattern"
      description """Updates a pattern resource. This can only be called by the user that created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutPatternRequest],
          Option[String]("Pattern object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putPatterns2 = (apiOperation[PostPutPatternRequest]("putPatterns2") summary("a") description("a"))  // for some bizarre reason, the PutPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/patterns/:pattern", operation(putPatterns)) ({
    val orgid = params("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    val ident = authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    val (valServiceIdActions, svcRefs) = patternReq.validateServiceIds  // to check that the services referenced exist
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) PatternsTQ.getPublic(pattern).result.asTry //getting public field from pattern
          else {
            val errStr = if (invalidIndex < svcRefs.length) ExchangeMessage.translateMessage("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
            else ExchangeMessage.translateMessage("service.not.in.exchange.index", Nth(invalidIndex+1))
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns"+barePattern+" checking public field of "+pattern+": "+xs)
      xs match {
        case Success(patternPublic) => val public = patternPublic
          if(public.nonEmpty){
            if (public.head || patternReq.public.getOrElse(false)) {    // pattern is public so need to check orgType
              OrgsTQ.getOrgType(orgid).result.asTry // should return a vector of Strings
            } else { // pattern isn't public so skip orgType check
              DBIO.successful(Vector("IBM")).asTry
            }
          } else {
            DBIO.failed(new NotFoundException(HttpCode.NOT_FOUND, ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))).asTry //gives 500 instead of 404
          }

        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns"+barePattern+" checking orgType of "+orgid+": "+xs)
      xs match {
        case Success(orgTypes) =>
          if (orgTypes.head == "IBM") {    // only patterns of orgType "IBM" can be public
            patternReq.toPatternRow(pattern, orgid, owner).update.asTry
          } else {
            DBIO.failed(new BadInputException(HttpCode.BAD_INPUT, ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("only.ibm.patterns.can.be.public"))).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.patternsOwner.putOne(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
              AuthCache.patterns.putIsPublic(pattern, patternReq.public.getOrElse(false))
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("pattern.not.updated", pattern, e)) }    // the specific exception is NumberFormatException
        case Failure(t) =>
          if(t.getMessage.contains("not found")){
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.not.updated", pattern, t.getMessage))
          }

      }
    })
  })

  // =========== PATCH /orgs/{orgid}/patterns/{pattern} ===============================
  val patchPatterns =
    (apiOperation[Map[String,String]]("patchPatterns")
      summary "Updates 1 attribute of a pattern"
      description """Updates one attribute of a pattern in the exchange DB. This can only be called by the user that originally created this pattern resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchPatternRequest],
          Option[String]("Partial pattern object that contains an attribute to be updated in this pattern. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchPatterns2 = (apiOperation[PatchPatternRequest]("patchPatterns2") summary("a") description("a"))  // for some bizarre reason, the PatchPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/patterns/:pattern", operation(patchPatterns)) ({
    val orgid = params("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PatchPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/patterns/"+barePattern+" input: "+patternReq.toString)
    val resp = response
    val (action, attrName) = patternReq.getDbUpdate(pattern, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("no.valid.pattern.attribute.specified")))
    val (valServiceIdActions, svcRefs) = if (attrName == "services") PatternsTQ.validateServiceIds(patternReq.services.get, List())
      else if (attrName == "userInput") PatternsTQ.validateServiceIds(List(), patternReq.userInput.get)
      else (DBIO.successful(Vector()), Vector())
    db.run(valServiceIdActions.asTry.flatMap({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) PatternsTQ.getPublic(pattern).result.asTry //getting public field from pattern
          else {
            val errStr = if (invalidIndex < svcRefs.length) ExchangeMessage.translateMessage("service.not.in.exchange.no.index", svcRefs(invalidIndex).org, svcRefs(invalidIndex).url, svcRefs(invalidIndex).versionRange, svcRefs(invalidIndex).arch)
            else ExchangeMessage.translateMessage("service.not.in.exchange.index", Nth(invalidIndex+1))
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/patterns"+barePattern+" checking public field of "+pattern+": "+xs)
      xs match {
        case Success(patternPublic) => val public = patternPublic
          if(public.nonEmpty) {
            val publicField = patternReq.public.getOrElse(false)
            if ((public.head && publicField) || publicField) { // pattern is public so need to check owner
              OrgsTQ.getOrgType(orgid).result.asTry
            } else { // pattern isn't public so skip orgType check
              DBIO.successful(Vector("IBM")).asTry
            }
          } else {
            DBIO.failed(new NotFoundException(HttpCode.NOT_FOUND, ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/patterns"+barePattern+" checking orgType of "+orgid+": "+xs)
      xs match {
        case Success(patternOrg) =>
          if (patternOrg.head == "IBM") {    // only patterns of orgType "IBM" can be public
            action.transactionally.asTry
          }
          else {
            DBIO.failed(new BadInputException(HttpCode.BAD_INPUT, ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("only.ibm.patterns.can.be.public"))).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (attrName == "public") AuthCache.patterns.putIsPublic(pattern, patternReq.public.getOrElse(false))
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.attribute.not.update", attrName, pattern))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("unexpected.result.from.update", e)) }
        case Failure(t) =>
          if(t.getMessage.contains("not found")){
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.not.updated", pattern, t.getMessage))
          }
      }
    })
  })

  // ======== POST /org/{orgid}/patterns/{pat-id}/search ========================
  val postPatternSearch =
    (apiOperation[PostPatternSearchResponse]("postPatternSearch")
      summary("Returns matching nodes of a particular pattern")
      description """Returns the matching nodes that are using this pattern and do not already have an agreement for the specified service. Can be run by a user or agbot (but not a node). The **request body** structure:

```
{
  "serviceUrl": "myorg/mydomain.com.sdr",   // The service that the node does not have an agreement with yet. Composite svc url (org/svc)
  "nodeOrgids": [ "org1", "org2", "..." ],   // if not specified, defaults to the same org the pattern is in
  "secondsStale": 60,     // max number of seconds since the exchange has heard from the node, 0 if you do not care
  "startIndex": 0,    // for pagination, ignored right now
  "numEntries": 0    // ignored right now
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of an agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPatternSearchRequest],
        Option[String]("Search criteria to find matching nodes in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postPatternSearch2 = (apiOperation[PostPatternSearchRequest]("postPatternSearch2") summary("a") description("a"))

  /** Normally called by the agbot to search for available nodes. */
  post("/orgs/:orgid/patterns/:pattern/search", operation(postPatternSearch)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositePat = OrgAndId(orgid,pattern).toString
    authenticate().authorizeTo(TNode(OrgAndId(orgid,"*").toString),Access.READ)
    val searchProps = try { parse(request.body).extract[PostPatternSearchRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("error.parsing.input.json", e))) }    // the specific exception is MappingException
    searchProps.validate()
    val nodeOrgids = searchProps.nodeOrgids.getOrElse(List(orgid)).toSet
    logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search criteria: "+searchProps.toString)
    val searchSvcUrl = searchProps.serviceUrl   // this now is a composite value (org/url), but plain url is supported for backward compat
    val resp = response
    /*
      Narrow down the db query results as much as possible by joining the Nodes and NodeAgreements tables and filtering.
      In english, the join gets: n.id, n.msgEndPoint, n.publicKey, a.serviceUrl, a.state
      The filters are: n is in the given list of node orgs, n.pattern==ourpattern, the node is not stale, there is an agreement for this node (the filter a.state=="" is applied later in our code below)
      Then we have to go thru all of the results and find nodes that do NOT have an agreement for searchSvcUrl.
      Note about Slick usage: joinLeft returns node rows even if they don't have any agreements (which means the agreement cols are Option() )
    */
    val oldestTime = if (searchProps.secondsStale > 0) ApiTime.pastUTC(searchProps.secondsStale) else ApiTime.beginningUTC

    def isEqualUrl(agrSvcUrl: String, searchSvcUrl: String): Boolean = {
      if (agrSvcUrl == searchSvcUrl) return true    // this is the relevant check when both agbot and agent are recent enough to use composite urls (org/org)
      // Assume searchSvcUrl is the new composite format (because the agbot is at least as high version as the agent) and strip off the org
      val reg = """^\S+?/(\S+)$""".r
      searchSvcUrl match {
        case reg(url) => return agrSvcUrl == url
        case _ => return false    // searchSvcUrl was not composite, so the urls are not equal
      }
    }

    db.run(PatternsTQ.getServices(compositePat).result.flatMap({ list =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search getServices size: "+list.size)
      logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/search: looking for '"+searchSvcUrl+"', searching getServices: "+list.toString())
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
        // archList.contains(n.arch.toString()
        if (found) {
          if (svcArch == "" || svcArch == "*" || archSet("") || archSet("*")){
            val nodeQuery =
              for {
                (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
              } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
            nodeQuery.result.asTry
          } else {
            val nodeQuery =
              for {
                (n, a) <- NodesTQ.rows.filter(_.orgid inSet(nodeOrgids)).filter(_.pattern === compositePat).filter(_.publicKey =!= "").filter(_.lastHeartbeat >= oldestTime).filter(_.arch inSet(archSet)) joinLeft NodeAgreementsTQ.rows on (_.id === _.nodeId)
              } yield (n.id, n.msgEndPoint, n.publicKey, a.map(_.agrSvcUrl), a.map(_.state))
            nodeQuery.result.asTry
          }
        }
//        else DBIO.failed(new Throwable("the serviceUrl '"+searchSvcUrl+"' specified in search body does not exist in pattern '"+compositePat+"'")).asTry
        else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("service.not.in.pattern"))).asTry

      }
      else DBIO.failed(new Throwable(ExchangeMessage.translateMessage("pattern.id.not.found", compositePat))).asTry
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+pattern+"/search result size: "+xs.getOrElse(Vector()).size)
      //logger.trace("POST /orgs/"+orgid+"/patterns/"+pattern+"/search result: "+xs.toString)
      xs match {
        case Success(list) => if (list.nonEmpty) {
          // Go thru the rows and build a hash of the nodes that do NOT have an agreement for our service
          val nodeHash = new MutableHashMap[String,PatternSearchHashElement]     // key is node id, value noAgreementYet which is true if so far we haven't hit an agreement for our service for this node
          for ( (nodeid, msgEndPoint, publicKey, agrSvcUrlOpt, stateOpt) <- list ) {
            //logger.trace("nodeid: "+nodeid+", agrSvcUrlOpt: "+agrSvcUrlOpt.getOrElse("")+", searchSvcUrl: "+searchSvcUrl+", stateOpt: "+stateOpt.getOrElse(""))
            nodeHash.get(nodeid) match {
              case Some(_) => if (isEqualUrl(agrSvcUrlOpt.getOrElse(""), searchSvcUrl) && stateOpt.getOrElse("") != "") { /*logger.trace("setting to false");*/ nodeHash.put(nodeid, PatternSearchHashElement(msgEndPoint, publicKey, noAgreementYet = false)) }  // this is no longer a candidate
              case None => val noAgr = if (isEqualUrl(agrSvcUrlOpt.getOrElse(""), searchSvcUrl) && stateOpt.getOrElse("") != "") false else true
                nodeHash.put(nodeid, PatternSearchHashElement(msgEndPoint, publicKey, noAgr))   // this node nodeid not in the hash yet, add it
            }
          }
          // Convert our hash to the list response of the rest api
          //val respList = list.map( x => PatternNodeResponse(x._1, x._2, x._3)).toList
          val respList = new ListBuffer[PatternNodeResponse]
          for ( (k, v) <- nodeHash) if (v.noAgreementYet) respList += PatternNodeResponse(k, v.msgEndPoint, v.publicKey)
          if (respList.nonEmpty) resp.setStatus(HttpCode.POST_OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          PostPatternSearchResponse(respList.toList, 0)
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          PostPatternSearchResponse(List[PatternNodeResponse](), 0)
        }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("invalid.input.message", t.getMessage))
      }
    })
  })


  // =========== DELETE /orgs/{orgid}/patterns/{pattern} ===============================
  val deletePatterns =
    (apiOperation[ApiResponse]("deletePatterns")
      summary "Deletes a pattern"
      description "Deletes a pattern from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/patterns/:pattern", operation(deletePatterns)) ({
    val orgid = params("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(PatternsTQ.getPattern(pattern).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.patternsOwner.removeOne(pattern)
            AuthCache.patterns.removeIsPublic(pattern)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.id.not.found", pattern))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("pattern.id.not.deleted", pattern, t.toString))
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/patterns/{pattern}/keys ================================ */
  val getPatternKeys =
    (apiOperation[List[String]]("getPatternKeys")
      summary "Returns all keys/certs for this pattern"
      description """Returns all the signing public keys/certs for this pattern. Can be run by any credentials able to view the pattern."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/patterns/:pattern/keys", operation(getPatternKeys)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    authenticate().authorizeTo(TPattern(compositeId),Access.READ)
    val resp = response
    db.run(PatternKeysTQ.getKeys(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/patterns/"+id+"/keys result: "+list.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      list.map(_.keyId)
    })
  })

  /* ====== GET /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ================================ */
  val getOnePatternKey =
    (apiOperation[String]("getOnePatternKey")
      summary "Returns a key/cert for this pattern"
      description """Returns the signing public key/cert with the specified keyid for this pattern. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the pattern."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/patterns/:pattern/keys/:keyid", operation(getOnePatternKey)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TPattern(compositeId),Access.READ)
    val resp = response
    db.run(PatternKeysTQ.getKey(compositeId, keyId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" result: "+list.size)
      if (list.nonEmpty) {
        // Return the raw key, not json
        resp.setStatus(HttpCode.OK)
        resp.setHeader("Content-Disposition", "attachment; filename="+keyId)
        resp.setHeader("Content-Type", "text/plain")
        resp.setHeader("Content-Length", list.head.key.length.toString)
        list.head.key
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("key.not.found", keyId))
      }
    })
  })

  // =========== PUT /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ===============================
  val putPatternKey =
    (apiOperation[ApiResponse]("putPatternKey")
      summary "Adds/updates a key/cert for the pattern"
      description """Adds a new signing public key/cert, or updates an existing key/cert, for this pattern. This can only be run by the pattern owning user. Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[String],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putPatternKey2 = (apiOperation[String]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/patterns/:pattern/keys/:keyid", operation(putPatternKey)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val keyReq = PutPatternKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutPatternKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toPatternKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("key.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, ExchangeMessage.translateMessage("pattern.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/patterns/{pattern}/keys ===============================
  val deletePatternAllKey =
    (apiOperation[ApiResponse]("deletePatternAllKey")
      summary "Deletes all keys of a pattern"
      description "Deletes all of the current keys/certs for this pattern. This can only be run by the pattern owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/patterns/:pattern/keys", operation(deletePatternAllKey)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val resp = response
    db.run(PatternKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /patterns/"+pattern+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.keys.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("no.pattern.keys.found", compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("pattern.keys.not.deleted", compositeId, t.toString))
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/patterns/{pattern}/keys/{keyid} ===============================
  val deletePatternKey =
    (apiOperation[ApiResponse]("deletePatternKey")
      summary "Deletes a key of a pattern"
      description "Deletes a key/cert for this pattern. This can only be run by the pattern owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/patterns/:pattern/keys/:keyid", operation(deletePatternKey)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val resp = response
    db.run(PatternKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /patterns/"+pattern+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, ExchangeMessage.translateMessage("pattern.key.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, ExchangeMessage.translateMessage("pattern.key.not.found", keyId, compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("pattern.key.not.deleted", keyId, compositeId, t.toString))
      }
    })
  })



}