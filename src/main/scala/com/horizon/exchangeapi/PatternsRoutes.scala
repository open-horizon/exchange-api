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

//====== These are the input and output structures for /orgs/{orgid}/patterns routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/patterns */
case class GetPatternsResponse(patterns: Map[String,Pattern], lastIndex: Int)
case class GetPatternAttributeResponse(attribute: String, value: String)

/** Input format for POST /microservices or PUT /orgs/{orgid}/patterns/<pattern-id> */
//case class PostPutPatternRequest(label: String, description: String, public: Boolean, microservices: List[Map[String,String]], workloads: List[PWorkloads], dataVerification: PDataVerification, agreementProtocols: List[Map[String,String]], properties: List[Map[String,Any]], counterPartyProperties: Map[String,List[Map[String,Any]]], maxAgreements: Int) {
case class PostPutPatternRequest(label: String, description: String, public: Boolean, workloads: List[PWorkloads], agreementProtocols: List[Map[String,String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {}

  //def toPatternRow(pattern: String, orgid: String, owner: String) = PatternRow(pattern, orgid, owner, label, description, public, write(microservices), write(workloads), write(dataVerification), write(agreementProtocols), write(properties), write(counterPartyProperties), maxAgreements, ApiTime.nowUTC)
  def toPatternRow(pattern: String, orgid: String, owner: String) = PatternRow(pattern, orgid, owner, label, description, public, write(workloads), write(agreementProtocols), ApiTime.nowUTC)
}

//case class PatchPatternRequest(label: Option[String], description: Option[String], public: Option[Boolean], microservices: Option[List[Map[String,String]]], workloads: Option[List[PWorkloads]], dataVerification: Option[PDataVerification], agreementProtocols: Option[List[Map[String,String]]], properties: Option[List[Map[String,Any]]], counterPartyProperties: Option[Map[String,List[Map[String,Any]]]], maxAgreements: Option[Int]) {
case class PatchPatternRequest(label: Option[String], description: Option[String], public: Option[Boolean], workloads: Option[List[PWorkloads]], agreementProtocols: Option[List[Map[String,String]]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the pattern, and the attribute name being updated. */
  def getDbUpdate(pattern: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this pattern
    label match { case Some(lab) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.label,d.lastUpdated)).update((pattern, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.description,d.lastUpdated)).update((pattern, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.public,d.lastUpdated)).update((pattern, pub, lastUpdated)), "public"); case _ => ; }
    //microservices match { case Some(ms) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.microservices,d.lastUpdated)).update((pattern, write(ms), lastUpdated)), "microservices"); case _ => ; }
    workloads match { case Some(wk) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.workloads,d.lastUpdated)).update((pattern, write(wk), lastUpdated)), "workloads"); case _ => ; }
    //dataVerification match { case Some(dv) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.dataVerification,d.lastUpdated)).update((pattern, write(dv), lastUpdated)), "dataVerification"); case _ => ; }
    agreementProtocols match { case Some(ap) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.agreementProtocols,d.lastUpdated)).update((pattern, write(ap), lastUpdated)), "agreementProtocols"); case _ => ; }
    //properties match { case Some(prop) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.properties,d.lastUpdated)).update((pattern, write(prop), lastUpdated)), "properties"); case _ => ; }
    //counterPartyProperties match { case Some(cpp) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.counterPartyProperties,d.lastUpdated)).update((pattern, write(cpp), lastUpdated)), "counterPartyProperties"); case _ => ; }
    //maxAgreements match { case Some(maxa) => return ((for { d <- PatternsTQ.rows if d.pattern === pattern } yield (d.pattern,d.maxAgreements,d.lastUpdated)).update((pattern, maxa, lastUpdated)), "maxAgreements"); case _ => ; }
    return (null, null)
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
      notes("""Returns all pattern definitions in this organization. Can be run by any user, node, or agbot.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("idfilter", DataType.String, Option[String]("Filter results to only include patterns with this id (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include patterns with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("label", DataType.String, Option[String]("Filter results to only include patterns with this label (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("description", DataType.String, Option[String]("Filter results to only include patterns with this description (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/patterns", operation(getPatterns)) ({
    val orgid = swaggerHack("orgid")
    credsAndLog().authenticate().authorizeTo(TPattern(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    //var q = PatternsTQ.rows.subquery
    var q = PatternsTQ.getAllPatterns(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("idfilter").foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("label").foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
    params.get("description").foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/patterns result size: "+list.size)
      val patterns = new MutableHashMap[String,Pattern]
      if (list.nonEmpty) for (a <- list) patterns.put(a.pattern, a.toPattern)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetPatternsResponse(patterns.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/patterns/{pattern} ================================ */
  val getOnePattern =
    (apiOperation[GetPatternsResponse]("getOnePattern")
      summary("Returns a pattern")
      notes("""Returns the pattern with the specified id in the exchange DB. Can be run by a user, node, or agbot.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire pattern resource will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/patterns/:pattern", operation(getOnePattern)) ({
    val orgid = swaggerHack("orgid")
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
            GetPatternAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole pattern resource
        db.run(PatternsTQ.getPattern(pattern).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/patterns/"+barePattern+" result: "+list.toString)
          val patterns = new MutableHashMap[String,Pattern]
          if (list.nonEmpty) for (a <- list) patterns.put(a.pattern, a.toPattern)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetPatternsResponse(patterns.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/patterns/{pattern} ===============================
  val postPatterns =
    (apiOperation[ApiResponse]("postPatterns")
      summary "Adds a pattern"
      notes """Creates a pattern resource. This can only be called by a user. The **request body** structure:

```
{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "workloads": [
    {
      "workloadUrl": "https://bluehorizon.network/workloads/weather",
      "workloadOrgid": "myorg",
      "workloadArch": "amd64",
      "workloadVersions": [
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
        "interval": 240,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      }
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ]
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutPatternRequest],
        Option[String]("Pattern object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postPatterns2 = (apiOperation[PostPutPatternRequest]("postPatterns2") summary("a") notes("a"))  // for some bizarre reason, the PostPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/patterns/:pattern", operation(postPatterns)) ({
    val orgid = swaggerHack("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    val ident = credsAndLog().authenticate().authorizeTo(TPattern(OrgAndId(orgid,"").toString),Access.CREATE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(PatternsTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns num owned by "+owner+": "+xs)
      val numOwned = xs
      val maxPatterns = ExchConfig.getInt("api.limits.maxPatterns")
      if (numOwned <= maxPatterns) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
        patternReq.toPatternRow(pattern, orgid, owner).insert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxPatterns+ " patterns")).asTry
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.patterns.putOwner(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "pattern '"+pattern+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "pattern '" + pattern + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "pattern '" + pattern + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not created: "+t.toString)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/patterns/{pattern} ===============================
  val putPatterns =
    (apiOperation[ApiResponse]("putPatterns")
      summary "Updates a pattern"
      notes """Updates a pattern resource. This can only be called by the user that created it. The **request body** structure:

```
{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "workloads": [
    {
      "workloadUrl": "https://bluehorizon.network/workloads/weather",
      "workloadOrgid": "myorg",
      "workloadArch": "amd64",
      "workloadVersions": [
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
        "interval": 240,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      }
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ]
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutPatternRequest],
        Option[String]("Pattern object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val putPatterns2 = (apiOperation[PostPutPatternRequest]("putPatterns2") summary("a") notes("a"))  // for some bizarre reason, the PutPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/patterns/:pattern", operation(putPatterns)) ({
    val orgid = swaggerHack("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    val ident = credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PostPutPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    patternReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(patternReq.toPatternRow(pattern, orgid, owner).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.patterns.putOwner(pattern, owner)     // currently only users are allowed to update pattern resources, so owner should never be blank
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "pattern updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "pattern '"+pattern+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not updated: "+t.toString)
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/patterns/{pattern} ===============================
  val patchPatterns =
    (apiOperation[Map[String,String]]("patchPatterns")
      notes """Updates one attribute of a pattern in the exchange DB. This can only be called by the user that originally created this pattern resource. The **request body** structure can include **1 of these attributes**:

```
{
  "label": "name of the edge pattern",
  "description": "descriptive text",
  "public": false,
  "workloads": [
    {
      "workloadUrl": "https://bluehorizon.network/workloads/weather",
      "workloadOrgid": "myorg",
      "workloadArch": "amd64",
      "workloadVersions": [
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
        "interval": 240,
        "check_rate": 15,
        "metering": {
          "tokens": 1,
          "per_time_unit": "min",
          "notification_interval": 30
        }
      }
    }
  ],
  "agreementProtocols": [
    {
      "name": "Basic"
    }
  ]
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchPatternRequest],
          Option[String]("Partial pattern object that contains an attribute to be updated in this pattern. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchPatterns2 = (apiOperation[PatchPatternRequest]("patchPatterns2") summary("a") notes("a"))  // for some bizarre reason, the PatchPatternRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/patterns/:pattern", operation(patchPatterns)) ({
    val orgid = swaggerHack("orgid")
    val barePattern = params("pattern")   // but do not have a hack/fix for the name
    val pattern = OrgAndId(orgid,barePattern).toString
    credsAndLog().authenticate().authorizeTo(TPattern(pattern),Access.WRITE)
    val patternReq = try { parse(request.body).extract[PatchPatternRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/patterns/"+barePattern+" input: "+patternReq.toString)
    val resp = response
    val (action, attrName) = patternReq.getDbUpdate(pattern, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid pattern attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/patterns/"+barePattern+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of pattern '"+pattern+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "pattern '"+pattern+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "pattern '"+pattern+"' not updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/patterns/{pattern} ===============================
  val deletePatterns =
    (apiOperation[ApiResponse]("deletePatterns")
      summary "Deletes a pattern"
      notes "Deletes a pattern from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("pattern", DataType.String, Option[String]("Pattern id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/patterns/:pattern", operation(deletePatterns)) ({
    val orgid = swaggerHack("orgid")
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

}