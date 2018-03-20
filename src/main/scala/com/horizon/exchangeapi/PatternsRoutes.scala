/** Services routes for all of the /orgs/{orgid}/patterns api methods. */
package com.horizon.exchangeapi

import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._
import com.horizon.exchangeapi.tables._

import scala.collection.immutable._
import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.util._
//import java.net._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/patterns routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/patterns */
case class GetPatternsResponse(patterns: Map[String,Pattern], lastIndex: Int)
case class GetPatternAttributeResponse(attribute: String, value: String)

/** Input format for POST/PUT /orgs/{orgid}/patterns/<pattern-id> */
case class PostPutPatternRequest(label: String, description: String, public: Boolean, workloads: Option[List[PWorkloads]], services: Option[List[PServices]], agreementProtocols: List[Map[String,String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(): Unit = {
    if (services.isDefined) {
      if (workloads.isDefined) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "can not specify both the 'services' and 'workloads' fields."))
      // Check that it is signed and check the version syntax
      for (s <- services.get) {
        for (sv <- s.serviceVersions) {
          if (!Version(sv.version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '" + sv.version + "' is not valid version format."))
          if (sv.deployment_overrides != "" && sv.deployment_overrides_signature == "") {
            halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this pattern definition does not appear to be signed."))
          }
        }
      }
    } else if (workloads.isDefined) {
      if (services.isDefined) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "can not specify both the 'services' and 'workloads' fields."))
      // Check that it is signed and check the version syntax
      for (w <- workloads.get) {
        for (wv <- w.workloadVersions) {
          if (!Version(wv.version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '" + wv.version + "' is not valid version format."))
          if (wv.deployment_overrides != "" && wv.deployment_overrides_signature == "") {
            halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this pattern definition does not appear to be signed."))
          }
        }
      }
    } else {
      halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either the 'services' or 'workloads' field must be specified."))
    }
  }

  // Build a list of db actions to verify that the referenced workloads exist
  def validateServiceIds: DBIO[Vector[Int]] = {
    if (services.isDefined) PatternsTQ.validateServiceIds(services.get)
    else if (workloads.isDefined) PatternsTQ.validateWorkloadIds(workloads.get)
    else halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "either the 'services' or 'workloads' field must be specified."))
  }

  // Note: write() handles correctly the case where the optional fields are None.
  def toPatternRow(pattern: String, orgid: String, owner: String) = PatternRow(pattern, orgid, owner, label, description, public, write(workloads), write(services), write(agreementProtocols), ApiTime.nowUTC)
  /* This is what to do if we want to fill in a default value for nodeHealth when it is not specified...
  def toPatternRow(pattern: String, orgid: String, owner: String): PatternRow = {
    // The nodeHealth field is optional, so fill in a default in each element of workloads if not specified. (Otherwise json4s will omit it in the DB and the GETs.)
    val workloads2 = workloads.map({ w =>
      val nodeHealth2 = if (w.nodeHealth.nonEmpty) w.nodeHealth else Some(Map[String,Int]())
      PWorkloads(w.workloadUrl, w.workloadOrgid, w.workloadArch, w.workloadVersions, w.dataVerification, nodeHealth2)
    })
    PatternRow(pattern, orgid, owner, label, description, public, write(workloads2), write(agreementProtocols), ApiTime.nowUTC)
  }
  */
}

case class PatchPatternRequest(label: Option[String], description: Option[String], public: Option[Boolean], workloads: Option[List[PWorkloads]], services: Option[List[PServices]], agreementProtocols: Option[List[Map[String,String]]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the pattern, and the attribute name being updated. */
  def getDbUpdate(pattern: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this pattern
    label match { case Some(lab) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.label,d.lastUpdated)).update((pattern, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.description,d.lastUpdated)).update((pattern, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.public,d.lastUpdated)).update((pattern, pub, lastUpdated)), "public"); case _ => ; }
    workloads match { case Some(wk) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.workloads,d.lastUpdated)).update((pattern, write(wk), lastUpdated)), "workloads"); case _ => ; }
    services match { case Some(svc) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.services,d.lastUpdated)).update((pattern, write(svc), lastUpdated)), "services"); case _ => ; }
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
    val ident = credsAndLog().authenticate().authorizeTo(TPattern(OrgAndId(orgid,"*").toString),Access.READ)
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
    credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the pattern
        val q = PatternsTQ.getAttribute(pattern, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Pattern attribute name '"+attribute+"' is not an attribute of the pattern resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/patterns/"+barePattern+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetPatternAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole pattern resource
        db.run(PatternsTQ.getPattern(pattern).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/patterns/"+barePattern+" result: "+list.size)
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
      description """Creates a pattern resource. A pattern resource specifies all of the services that should be deployed for a type of node. When a node registers with Horizon, it can specify a pattern name to quickly tell Horizon what should be deployed on it. Patterns are not typically intended to be shared across organizations because they also specify deployment policy. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
{
  "label": "name of the edge pattern",     // this will be displayed in the node registration UI
  "description": "descriptive text",
  "public": false,       // typically patterns are not appropriate to share across orgs because they contain policy choices
  // The services that should be deployed to the edge for this pattern. (The services must exist before creating this pattern.)
  "services": [
    {
      "serviceUrl": "https://bluehorizon.network/services/weather",
      "serviceOrgid": "myorg",
      "serviceArch": "amd64",
      // If multiple service versions are listed, Horizon will try to automatically upgrade nodes to the version with the lowest priority_value number
      "serviceVersions": [
        {
          "version": "1.0.1",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "",     // filled in by the Horizon signing process
          "priority": {
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          // When Horizon should upgrade nodes to newer service versions. Can be set to {} to take the default of immediate.
          "upgradePolicy": {
            "lifecycle": "immediate",
            "time": "01:00AM"     // reserved for future use
          }
        }
      ],
      // Fill in this section if the Horizon agbot should run a REST API of the cloud data ingest service to confirm the service is sending data.
      // If not using this, the dataVerification field can be set to {} or omitted completely.
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
      // If not using agbot node health check, this field can be set to {} or omitted completely.
      "nodeHealth": {
        "missing_heartbeat_interval": 600,      // How long a node heartbeat can be missing before cancelling its agreements (in seconds)
        "check_agreement_status": 120        // How often to check that the node agreement entry still exists, and cancel agreement if not found (in seconds)
      }
    }
  ],
  "workloads": [],     // same as services except with s/service/workload/
  // The Horizon agreement protocol(s) to use. "Basic" means make agreements w/o a blockchain. "Citizen Scientist" means use ethereum to record the agreement.
  "agreementProtocols": [
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
    val ident = credsAndLog().authenticate().authorizeTo(TPattern(OrgAndId(orgid,"").toString),Access.CREATE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    // Get optional agbots that should be updated with this new pattern
    //val agbotParams = multiParams("updateagbot")
    val resp = response
    // validateServiceIds() checks either the services or workloads, whichever is defined
    db.run(patternReq.validateServiceIds.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) PatternsTQ.getNumOwned(owner).result.asTry
          else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange")).asTry
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
          else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxPatterns+ " patterns")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.patterns.putOwner(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
          AuthCache.patterns.putIsPublic(pattern, patternReq.public)
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "pattern '"+pattern+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "pattern '" + pattern + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "pattern '" + pattern + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "pattern '"+pattern+"' not created: "+t.getMessage)
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
    val ident = credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(patternReq.validateServiceIds.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) patternReq.toPatternRow(pattern, orgid, owner).update.asTry
          else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.patterns.putOwner(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
              AuthCache.patterns.putIsPublic(pattern, patternReq.public)
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "pattern updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "pattern '"+pattern+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "pattern '"+pattern+"' not updated: "+t.getMessage)
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
    credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PatchPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/patterns/"+barePattern+" input: "+patternReq.toString)
    val resp = response
    val (action, attrName) = patternReq.getDbUpdate(pattern, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid pattern attribute specified"))
    val patValidateAction = if (attrName == "workloads") PatternsTQ.validateWorkloadIds(patternReq.workloads.get) else if (attrName == "services") PatternsTQ.validateServiceIds(patternReq.services.get) else DBIO.successful(Vector())
    db.run(patValidateAction.asTry.flatMap({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns"+barePattern+" service validation: "+xs.toString)
      xs match {
        case Success(v) => var invalidIndex = -1    // v is a vector of Int (the length of each service query). If any are zero we should error out.
          breakable { for ( (len, index) <- v.zipWithIndex) {
            if (len <= 0) {
              invalidIndex = index
              break
            }
          } }
          if (invalidIndex < 0) action.transactionally.asTry
          else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service does not exist in the exchange")).asTry
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
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of pattern '"+pattern+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "pattern '"+pattern+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "pattern '"+pattern+"' not updated: "+t.getMessage)
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
    credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(PatternsTQ.getPattern(pattern).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.patterns.removeOwner(pattern)
            AuthCache.patterns.removeIsPublic(pattern)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "pattern deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "pattern '"+pattern+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not deleted: "+t.toString)
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
    credsAndLog().authenticate().authorizeTo(TPattern(compositeId),Access.READ)
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
    credsAndLog().authenticate().authorizeTo(TPattern(compositeId),Access.READ)
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
        ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' not found")
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
        Parameter("body", DataType[PutPatternKeyRequest],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putPatternKey2 = (apiOperation[PutPatternKeyRequest]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/patterns/:pattern/keys/:keyid", operation(putPatternKey)) ({
    val orgid = params("orgid")
    val pattern = params("pattern")
    val compositeId = OrgAndId(orgid,pattern).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val keyReq = PutPatternKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutPatternKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toPatternKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns/"+pattern+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "key added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "key '"+keyId+"' for pattern '"+compositeId+"' not inserted or updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "key '"+keyId+"' for pattern '"+compositeId+"' not inserted or updated: "+t.getMessage)
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
    credsAndLog().authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val resp = response
    db.run(PatternKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /patterns/"+pattern+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "pattern keys deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "no keys for pattern '"+compositeId+"' found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "keys for pattern '"+compositeId+"' not deleted: "+t.toString)
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
    credsAndLog().authenticate().authorizeTo(TPattern(compositeId),Access.WRITE)
    val resp = response
    db.run(PatternKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /patterns/"+pattern+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "pattern key deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' for pattern '"+compositeId+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "key '"+keyId+"' for pattern '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

}