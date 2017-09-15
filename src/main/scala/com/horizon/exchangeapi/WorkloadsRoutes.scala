/** Services routes for all of the /orgs/{orgid}/workloads api methods. */
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
import java.net._

//====== These are the input and output structures for /orgs/{orgid}/workloads routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/workloads */
case class GetWorkloadsResponse(workloads: Map[String,Workload], lastIndex: Int)
case class GetWorkloadAttributeResponse(attribute: String, value: String)

/** Input format for POST /microservices or PUT /orgs/{orgid}/workloads/<workload-id> */
case class PostPutWorkloadRequest(label: String, description: String, public: Boolean, workloadUrl: String, version: String, arch: String, downloadUrl: String, apiSpec: List[Map[String,String]], userInput: List[Map[String,String]], workloads: List[Map[String,String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    // Check the workloadUrl is a valid URL
    try {
      new URL(workloadUrl)
    } catch {
      case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "workloadUrl is not valid URL format."))
    }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version is not valid version format."))
  }

  def formId(orgid: String): String = {
    // Remove the https:// from the beginning of workloadUrl and replace troublesome chars with a dash. It has already been checked as a valid URL in validate().
    val workloadUrl2 = """^[A-Za-z0-9+.-]*?://""".r replaceFirstIn (workloadUrl, "")
    val workloadUrl3 = """[$!*,;/?@&~=%]""".r replaceAllIn (workloadUrl2, "-")     // I think possible chars in valid urls are: $_.+!*,;/?:@&~=%-
    return OrgAndId(orgid, workloadUrl3 + "_" + version + "_" + arch).toString
  }

  def toWorkloadRow(workload: String, orgid: String, owner: String) = WorkloadRow(workload, orgid, owner, label, description, public, workloadUrl, version, arch, downloadUrl, write(apiSpec), write(userInput), write(workloads), ApiTime.nowUTC)
}

case class PatchWorkloadRequest(label: Option[String], description: Option[String], public: Option[Boolean], workloadUrl: Option[String], version: Option[String], arch: Option[String], downloadUrl: Option[String]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the workload, and the attribute name being updated. */
  def getDbUpdate(workload: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    //todo: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this workload
    label match { case Some(lab) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.label,d.lastUpdated)).update((workload, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.description,d.lastUpdated)).update((workload, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.public,d.lastUpdated)).update((workload, pub, lastUpdated)), "description"); case _ => ; }
    workloadUrl match { case Some(url) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.workloadUrl,d.lastUpdated)).update((workload, url, lastUpdated)), "workloadUrl"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.version,d.lastUpdated)).update((workload, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.arch,d.lastUpdated)).update((workload, ar, lastUpdated)), "arch"); case _ => ; }
    downloadUrl match { case Some(url) => return ((for { d <- WorkloadsTQ.rows if d.workload === workload } yield (d.workload,d.downloadUrl,d.lastUpdated)).update((workload, url, lastUpdated)), "downloadUrl"); case _ => ; }
    return (null, null)
  }
}



/** Implementation for all of the /orgs/{orgid}/workloads routes */
trait WorkloadRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  /* ====== GET /orgs/{orgid}/workloads ================================ */
  val getWorkloads =
    (apiOperation[GetWorkloadsResponse]("getWorkloads")
      summary("Returns all workloads")
      notes("""Returns all workload definitions in the exchange DB. Can be run by any user, node, or agbot.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include workloads with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("workloadUrl", DataType.String, Option[String]("Filter results to only include workloads with this workloadUrl (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("version", DataType.String, Option[String]("Filter results to only include workloads with this version (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include workloads with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("specRef", DataType.String, Option[String]("Filter results to only include workloads that use this microservice specRef (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/workloads", operation(getWorkloads)) ({
    val orgid = swaggerHack("orgid")
    credsAndLog().authenticate().authorizeTo(TWorkload(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    //var q = WorkloadsTQ.rows.subquery
    var q = WorkloadsTQ.getAllWorkloads(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("workloadUrl").foreach(workloadUrl => { if (workloadUrl.contains("%")) q = q.filter(_.workloadUrl like workloadUrl) else q = q.filter(_.workloadUrl === workloadUrl) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    // We are cheating a little on this one because the whole apiSpec structure is serialized into a json string when put in the db, so it has a string value like
    // [{"specRef":"https://bluehorizon.network/documentation/microservice/rtlsdr","version":"1.0.0","arch":"amd64"}]. But we can still match on the specRef.
    params.get("specRef").foreach(specRef => {
      val specRef2 = "%\"specRef\":\"" + specRef + "\"%"
      q = q.filter(_.apiSpec like specRef2)
    })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/workloads result size: "+list.size)
      val workloads = new MutableHashMap[String,Workload]
      if (list.nonEmpty) for (a <- list) workloads.put(a.workload, a.toWorkload)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetWorkloadsResponse(workloads.toMap, 0)
    })
  })

  /* ====== GET /orgs/{orgid}/workloads/{workload} ================================ */
  val getOneWorkload =
    (apiOperation[GetWorkloadsResponse]("getOneWorkload")
      summary("Returns a workload")
      notes("""Returns the workload with the specified id in the exchange DB. Can be run by a user, node, or agbot.

- **Due to a swagger bug, the format shown below is incorrect. Run the GET method to see the response format instead.**""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("workload", DataType.String, Option[String]("Workload id."), paramType=ParamType.Query),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire workload resource will be returned."), paramType=ParamType.Query, required=false)
        )
      )

  get("/orgs/:orgid/workloads/:workload", operation(getOneWorkload)) ({
    val orgid = swaggerHack("orgid")
    val bareWorkload = params("workload")   // but do not have a hack/fix for the name
    val workload = OrgAndId(orgid,bareWorkload).toString
    credsAndLog().authenticate().authorizeTo(TWorkload(workload),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the workload
        val q = WorkloadsTQ.getAttribute(workload, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Workload attribute name '"+attribute+"' is not an attribute of the workload resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/workloads/"+bareWorkload+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            GetWorkloadAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole workload resource
        db.run(WorkloadsTQ.getWorkload(workload).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/workloads/"+bareWorkload+" result: "+list.toString)
          val workloads = new MutableHashMap[String,Workload]
          if (list.nonEmpty) for (a <- list) workloads.put(a.workload, a.toWorkload)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetWorkloadsResponse(workloads.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/workloads ===============================
  val postWorkloads =
    (apiOperation[ApiResponse]("postWorkloads")
      summary "Adds a workload"
      notes """Creates a workload resource. This can only be called by a user. The **request body** structure:

```
{
  "label": "Location for x86_64",     // for the registration UI
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "workloadUrl": "https://bluehorizon.network/documentation/workload/location",   // the unique identifier of this MS
  "version": "1.0.0",
  "arch": "amd64",
  "downloadUrl": "",    // not used yet
  "apiSpec": [
    {
      "specRef": "https://bluehorizon.network/documentation/microservice/gps",
      "version": "1.0.0",
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container. Can override env vars in workloads.deployment.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "list of strings"
      "defaultValue": "bar"
    }
  ],
  "workloads": [
    {
      "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
      Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutWorkloadRequest],
        Option[String]("Workload object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val postWorkloads2 = (apiOperation[PostPutWorkloadRequest]("postWorkloads2") summary("a") notes("a"))  // for some bizarre reason, the PostWorkloadRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/workloads", operation(postWorkloads)) ({
    val orgid = swaggerHack("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TWorkload(OrgAndId(orgid,"").toString),Access.CREATE)
    val workloadReq = try { parse(request.body).extract[PostPutWorkloadRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    workloadReq.validate()
    val workload = workloadReq.formId(orgid)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(WorkloadsTQ.getNumOwned(owner).result.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/workloads num owned by "+owner+": "+xs)
      val numOwned = xs
      val maxWorkloads = ExchConfig.getInt("api.limits.maxWorkloads")
      if (numOwned <= maxWorkloads) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
        workloadReq.toWorkloadRow(workload, orgid, owner).insert.asTry
      }
      else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxWorkloads+ " workloads")).asTry
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/workloads result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.workloads.putOwner(workload, owner)     // currently only users are allowed to update workload resources, so owner should never be blank
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "workload '"+workload+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "workload '" + workload + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "workload '" + workload + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "workload '"+workload+"' not created: "+t.toString)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/workloads/{workload} ===============================
  val putWorkloads =
    (apiOperation[ApiResponse]("putWorkloads")
      summary "Updates a workload"
      notes """Does a full replace of an existing workload. This can only be called by a user to create, and then only by that user to update. The **request body** structure:

```
{
  "label": "Location for x86_64",     // for the registration UI
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "workloadUrl": "https://bluehorizon.network/documentation/workload/location",   // the unique identifier of this MS
  "version": "1.0.0",
  "arch": "amd64",
  "downloadUrl": "",    // not used yet
  "apiSpec": [
    {
      "specRef": "https://bluehorizon.network/documentation/microservice/gps",
      "version": "1.0.0",
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container. Can override env vars in workloads.deployment.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "list of strings"
      "defaultValue": "bar"
    }
  ],
  "workloads": [
    {
      "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("workload", DataType.String, Option[String]("Workload id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutWorkloadRequest],
        Option[String]("Workload object that needs to be updated in the exchange. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      )
  val putWorkloads2 = (apiOperation[PostPutWorkloadRequest]("putWorkloads2") summary("a") notes("a"))  // for some bizarre reason, the PutWorkloadRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/workloads/:workload", operation(putWorkloads)) ({
    val orgid = swaggerHack("orgid")
    val bareWorkload = params("workload")   // but do not have a hack/fix for the name
    val workload = OrgAndId(orgid,bareWorkload).toString
    val ident = credsAndLog().authenticate().authorizeTo(TWorkload(workload),Access.WRITE)
    val workloadReq = try { parse(request.body).extract[PostPutWorkloadRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    workloadReq.validate()
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }
    val resp = response
    db.run(workloadReq.toWorkloadRow(workload, orgid, owner).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/workloads/"+bareWorkload+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.workloads.putOwner(workload, owner)     // currently only users are allowed to update workload resources, so owner should never be blank
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "workload updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "workload '"+workload+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "workload '"+workload+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "workload '"+workload+"' not updated: "+t.toString)
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/workloads/{workload} ===============================
  val patchWorkloads =
    (apiOperation[Map[String,String]]("patchWorkloads")
      summary "Partially updates a workload"
      notes """Updates one attribute of a workload in the exchange DB. This can only be called by the user that originally created this workload resource. The **request body** structure can include **1 of these attributes**:

```
{
  "label": "GPS x86_64",     // for the registration UI
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "workloadUrl": "https://bluehorizon.network/documentation/workload/gps",   // the unique identifier of this workload
  "version": "1.0.0",
  "arch": "amd64",
  "downloadUrl": ""    // not used yet
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("workload", DataType.String, Option[String]("Workload id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchWorkloadRequest],
          Option[String]("Partial workload object that contains an attribute to be updated in this workload. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      )
  val patchWorkloads2 = (apiOperation[PatchWorkloadRequest]("patchWorkloads2") summary("a") notes("a"))  // for some bizarre reason, the PatchWorkloadRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/workloads/:workload", operation(patchWorkloads)) ({
    val orgid = swaggerHack("orgid")
    val bareWorkload = params("workload")   // but do not have a hack/fix for the name
    val workload = OrgAndId(orgid,bareWorkload).toString
    credsAndLog().authenticate().authorizeTo(TWorkload(workload),Access.WRITE)
    val workloadReq = try { parse(request.body).extract[PatchWorkloadRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/workloads/"+bareWorkload+" input: "+workloadReq.toString)
    val resp = response
    val (action, attrName) = workloadReq.getDbUpdate(workload, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid workload attribute specified"))
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/workloads/"+bareWorkload+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of workload '"+workload+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "workload '"+workload+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "workload '"+workload+"' not updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/workloads/{workload} ===============================
  val deleteWorkloads =
    (apiOperation[ApiResponse]("deleteWorkloads")
      summary "Deletes a workload"
      notes "Deletes a workload from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Query),
        Parameter("workload", DataType.String, Option[String]("Workload id."), paramType=ParamType.Query),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Path, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      )

  delete("/orgs/:orgid/workloads/:workload", operation(deleteWorkloads)) ({
    val orgid = swaggerHack("orgid")
    val bareWorkload = params("workload")   // but do not have a hack/fix for the name
    val workload = OrgAndId(orgid,bareWorkload).toString
    credsAndLog().authenticate().authorizeTo(TWorkload(workload),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(WorkloadsTQ.getWorkload(workload).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/workloads/"+bareWorkload+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.workloads.removeOwner(workload)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "workload deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "workload '"+workload+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "workload '"+workload+"' not deleted: "+t.toString)
      }
    })
  })

}