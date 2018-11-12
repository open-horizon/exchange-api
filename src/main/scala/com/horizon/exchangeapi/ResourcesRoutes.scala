/** Resources routes for all of the /orgs/{orgid}/resources api methods. */
package com.horizon.exchangeapi

import java.net.{MalformedURLException, URL}

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

//====== These are the input and output structures for /orgs/{orgid}/resources routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/resources */
case class GetResourcesResponse(resources: Map[String,Resource], lastIndex: Int)
case class GetResourceAttributeResponse(attribute: String, value: String)

/** Input format for POST /orgs/{orgid}/resources or PUT /orgs/{orgid}/resources/<resource-id> */
case class PostPutResourceRequest(name: String, description: Option[String], public: Boolean, documentation: Option[String], version: String, arch: Option[String], sharable: String, deployment: String, deploymentSignature: String, resourceStore: Map[String,Any]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(orgid: String, resourceId: String) = {
    // Ensure that the documentation field is a valid URL
    if (documentation.getOrElse("") != "") {
      try { new URL(documentation.getOrElse("")) }
      catch { case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the 'documentation' field is not valid URL format.")) }
    }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '"+version+"' is not valid version format."))

    // We enforce that the attributes equal the existing id for PUT, because even if they change the attribute, the id would not get updated correctly
    if (resourceId != null && resourceId != "" && formId(orgid) != resourceId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the resource id specified in the URL does not match the name and version in the body."))

    val allSharableVals = SharableVals.values.map(_.toString)
    if (sharable == "" || !allSharableVals.contains(sharable)) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value '"+sharable+"' for the sharable attribute."))

    // Check that it is signed
    if (deployment != "" && deploymentSignature == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this resource definition does not appear to be signed."))

    // Ensure they specified url in resourceStore
    if (!resourceStore.keySet.contains("url")) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the 'resourceStore' field must at least contain the 'url' field."))
  }

  def formId(orgid: String) = ResourcesTQ.formId(orgid, name, version)

  def toResourceRow(resource: String, orgid: String, owner: String) = ResourceRow(resource, orgid, owner, name, description.getOrElse(name), public, documentation.getOrElse(""), version, arch.getOrElse(""), sharable, deployment, deploymentSignature, write(resourceStore), ApiTime.nowUTC)
}

case class PatchResourceRequest(name: Option[String], description: Option[String], public: Option[Boolean], documentation: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], deployment: Option[String], deploymentSignature: Option[String], resourceStore: Option[Map[String,Any]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the resource, and the attribute name being updated. */
  def getDbUpdate(resource: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this resource
    name match { case Some(lab) => return ((for { d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.name,d.lastUpdated)).update((resource, lab, lastUpdated)), "name"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.description,d.lastUpdated)).update((resource, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.public,d.lastUpdated)).update((resource, pub, lastUpdated)), "public"); case _ => ; }
    documentation match { case Some(doc) => return ((for {d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.documentation,d.lastUpdated)).update((resource, doc, lastUpdated)), "documentation"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.version,d.lastUpdated)).update((resource, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.arch,d.lastUpdated)).update((resource, ar, lastUpdated)), "arch"); case _ => ; }
    sharable match { case Some(share) => return ((for {d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.sharable,d.lastUpdated)).update((resource, share, lastUpdated)), "sharable"); case _ => ; }
    deployment match { case Some(dep) => return ((for {d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.deployment,d.lastUpdated)).update((resource, dep, lastUpdated)), "deployment"); case _ => ; }
    deploymentSignature match { case Some(depsig) => return ((for {d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.deploymentSignature,d.lastUpdated)).update((resource, depsig, lastUpdated)), "deploymentSignature"); case _ => ; }
    resourceStore match { case Some(p) => return ((for {d <- ResourcesTQ.rows if d.resource === resource } yield (d.resource,d.resourceStore,d.lastUpdated)).update((resource, write(p), lastUpdated)), "resourceStore"); case _ => ; }
    return (null, null)
  }
}


/** Input format for PUT /orgs/{orgid}/resources/{resource}/keys/<key-id> */
case class PutResourceKeyRequest(key: String) {
  def toResourceKey = ResourceKey(key, ApiTime.nowUTC)
  def toResourceKeyRow(resourceId: String, keyId: String) = ResourceKeyRow(keyId, resourceId, key, ApiTime.nowUTC)
  def validate(keyId: String) = {
    //if (keyId != formId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the key id should be in the form keyOrgid_key"))
  }
}


/** Response for GET /orgs/{orgid}/agbots/{id}/msgs */
//case class GetResourceAuthResponse(auths: List[ResourceAuth], lastIndex: Int)

/** Input format for POST /orgs/{orgid}/resources/{resource}/auths or PUT /orgs/{orgid}/resources/{resource}/auths/{authid} */
case class PostPutResourceAuthRequest(username: Option[String], token: String) {
  def toResourceAuthRow(resourceId: String, authId: Int) = ResourceAuthRow(authId, resourceId, username.getOrElse("token"), token, ApiTime.nowUTC)
  def validate(authId: Int) = { }
  def getDupAuth(resourceId: String) = ResourceAuthsTQ.getDupAuth(resourceId, username.getOrElse("token"), token)
}



/** Implementation for all of the /orgs/{orgid}/resources routes */
trait ResourceRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  // ====== GET /orgs/{orgid}/resources ================================
  val getResources =
    (apiOperation[GetResourcesResponse]("getResources")
      summary("Returns all resources")
      description("""Returns all resource definitions in the exchange DB. Can be run by any user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include resources with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("public", DataType.String, Option[String]("Filter results to only include resources with this public setting"), paramType=ParamType.Query, required=false),
        Parameter("version", DataType.String, Option[String]("Filter results to only include resources with this version (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include resources with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources", operation(getResources)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TResource(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    var q = ResourcesTQ.getAllResources(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("public").foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
    params.get("name").foreach(url => { if (url.contains("%")) q = q.filter(_.name like url) else q = q.filter(_.name === url) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/resources result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/resources result: "+list.toString())
      val resources = new MutableHashMap[String,Resource]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || a.public || ident.isSuperUser || ident.isMultiTenantAgbot) resources.put(a.resource, a.toResource)
      if (resources.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetResourcesResponse(resources.toMap, 0)
    })
  })

  // ====== GET /orgs/{orgid}/resources/{resource} ================================
  val getOneResource =
    (apiOperation[GetResourcesResponse]("getOneResource")
      summary("Returns a resource")
      description("""Returns the resource with the specified id in the exchange DB. Can be run by a user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire resource will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources/:resource", operation(getOneResource)) ({
    val orgid = params("orgid")
    val bareResource = params("resource")   // but do not have a hack/fix for the name
    val resource = OrgAndId(orgid,bareResource).toString
    authenticate().authorizeTo(TResource(resource),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the resource
        val q = ResourcesTQ.getAttribute(resource, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Resource attribute name '"+attribute+"' is not an attribute of the resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/resources/"+bareResource+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetResourceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole resource
        db.run(ResourcesTQ.getResource(resource).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/resources/"+bareResource+" result: "+list.toString)
          val resources = new MutableHashMap[String,Resource]
          if (list.nonEmpty) for (a <- list) resources.put(a.resource, a.toResource)
          if (resources.nonEmpty) resp.setStatus(HttpCode.OK)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetResourcesResponse(resources.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/resources ===============================
  val postResources =
    (apiOperation[ApiResponse]("postResources")
      summary "Adds a resource"
      description """Creates a resource. A resource points to a file (can be a tar file) that is needed by some services. Horizon will deploy this file with the services that require it. If public is set to true, the resource can be shared across organizations. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
{
  "name": "cat-faces-caffe-model",
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "documentation": "https://console.cloud.ibm.com/docs/resources/edge-fabric/poc/sdr-model.html",   // description of what this resource if for and how to use it
  "version": "1.0.0",
  "arch": "amd64",     // optional, can be omitted
  "sharable": "singleton",   // if multiple resources require this resource, how many instances are deployed: "exclusive", "singleton", "multiple"
  // Information about how to deploy the resource file to the service containers that require it
  "deployment": "{\"volumeMountPoint\":\"/models/mymodel",\"access\":\"readonly\"}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",     // filled in by the Horizon signing process
  "resourceStore": {
    "storeType": "slObjectStore",    // valid values: slObjectStore (default), http
    "packageType": "tarball",   // valid values: tarball (default)
    "url": "https://...",     // to download the resource file
    "signature": ""      // signature of the resource file
  }
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutResourceRequest],
          Option[String]("Resource object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postResources2 = (apiOperation[PostPutResourceRequest]("postResources2") summary("a") description("a"))  // for some bizarre reason, the PostResourceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/resources", operation(postResources)) ({
  //post("/orgs/:orgid/resources") ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TResource(OrgAndId(orgid,"").toString),Access.CREATE)
    val resourceReq = try { parse(request.body).extract[PostPutResourceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    resourceReq.validate(orgid, null)
    val resource = resourceReq.formId(orgid)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update resources, so owner will never be blank
    val resp = response

    db.run(ResourcesTQ.getNumOwned(owner).result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/resources num owned by "+owner+": "+xs)
      xs match {
        case Success(num) => val numOwned = num
          val maxResources = ExchConfig.getInt("api.limits.maxResources")
          if (maxResources == 0 || maxResources >= numOwned) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
            resourceReq.toResourceRow(resource, orgid, owner).insert.asTry
          }
          else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxResources+ " resources")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/resources result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.resources.putOwner(resource, owner)     // currently only users are allowed to update resources, so owner should never be blank
          AuthCache.resources.putIsPublic(resource, resourceReq.public)
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "resource '"+resource+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "resource '" + resource + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "resource '" + resource + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "resource '"+resource+"' not created: "+t.getMessage)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/resources/{resource} ===============================
  val putResources =
    (apiOperation[ApiResponse]("putResources")
      summary "Updates a resource"
      description """Does a full replace of an existing resource. See the description of the body fields in the POST method. This can only be called by the user that originally created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutResourceRequest],
          Option[String]("Resource object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putResources2 = (apiOperation[PostPutResourceRequest]("putResources2") summary("a") description("a"))  // for some bizarre reason, the PutResourceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/resources/:resource", operation(putResources)) ({
    val orgid = params("orgid")
    val bareResource = params("resource")   // but do not have a hack/fix for the name
    val resource = OrgAndId(orgid,bareResource).toString
    val ident = authenticate().authorizeTo(TResource(resource),Access.WRITE)
    val resourceReq = try { parse(request.body).extract[PostPutResourceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    resourceReq.validate(orgid, resource)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }     // currently only users are allowed to update resources, so owner should never be blank
    val resp = response

    db.run(resourceReq.toResourceRow(resource, orgid, owner).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/resources/"+bareResource+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.resources.putOwner(resource, owner)     // currently only users are allowed to update resources, so owner should never be blank
              AuthCache.resources.putIsPublic(resource, resourceReq.public)
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "resource updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "resource '"+resource+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "resource '"+resource+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "resource '" + resource + "' not updated: " + t.getMessage)
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "resource '" + resource + "' not updated: " + t.getMessage)
          }
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/resources/{resource} ===============================
  val patchResources =
    (apiOperation[Map[String,String]]("patchResources")
      summary "Updates 1 attribute of a resource"
      description """Updates one attribute of a resource in the exchange DB. This can only be called by the user that originally created this resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchResourceRequest],
          Option[String]("Partial resource object that contains 1 attribute to be updated in this resource."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchResources2 = (apiOperation[PatchResourceRequest]("patchResources2") summary("a") description("a"))  // for some bizarre reason, the PatchResourceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/resources/:resource", operation(patchResources)) ({
    val orgid = params("orgid")
    val bareResource = params("resource")   // but do not have a hack/fix for the name
    val resource = OrgAndId(orgid,bareResource).toString
    authenticate().authorizeTo(TResource(resource),Access.WRITE)
    val resourceReq = try { parse(request.body).extract[PatchResourceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/resources/"+bareResource+" input: "+resourceReq.toString)
    val resp = response
    val (action, attrName) = resourceReq.getDbUpdate(resource, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid resource attribute specified"))
    if (attrName == "name" || attrName == "version") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "patching attributes 'name' and 'version' are not allowed (because the id would not match). To change those attributes you must delete the resource and recreate it."))
    if (attrName == "sharable") {
      val allSharableVals = SharableVals.values.map(_.toString)
      if (!allSharableVals.contains(resourceReq.sharable.getOrElse(""))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value '" + resourceReq.sharable.getOrElse("") + "' for the sharable attribute."))
    }

    // First check that the requiredResources exist (if that is not watch they are patching, this is a noop)
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/resources/"+bareResource+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (attrName == "public") AuthCache.resources.putIsPublic(resource, resourceReq.public.getOrElse(false))
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of resource '"+resource+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "resource '"+resource+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, "resource '" + resource + "' not updated: " + t.getMessage)
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, "resource '" + resource + "' not updated: " + t.getMessage)
          }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/resources/{resource} ===============================
  val deleteResources =
    (apiOperation[ApiResponse]("deleteResources")
      summary "Deletes a resource"
      description "Deletes a resource from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/resources/:resource", operation(deleteResources)) ({
    val orgid = params("orgid")
    val bareResource = params("resource")   // but do not have a hack/fix for the name
    val resource = OrgAndId(orgid,bareResource).toString
    authenticate().authorizeTo(TResource(resource),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(ResourcesTQ.getResource(resource).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/resources/"+bareResource+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.resources.removeOwner(resource)
            AuthCache.resources.removeIsPublic(resource)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "resource deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "resource '"+resource+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "resource '"+resource+"' not deleted: "+t.toString)
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/resources/{resource}/keys ================================ */
  val getResourceKeys =
    (apiOperation[List[String]]("getResourceKeys")
      summary "Returns all keys/certs for this resource"
      description """Returns all the signing public keys/certs for this resource. These are used by the Horizon Agent to verify both the resource deployment string and the resource file. Can be run by any credentials able to view the resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources/:resource/keys", operation(getResourceKeys)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    authenticate().authorizeTo(TResource(compositeId),Access.READ)
    val resp = response
    db.run(ResourceKeysTQ.getKeys(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/resources/"+resource+"/keys result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/resources/"+id+"/keys result: "+list.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      list.map(_.keyId)
    })
  })

  /* ====== GET /orgs/{orgid}/resources/{resource}/keys/{keyid} ================================ */
  val getOneResourceKey =
    (apiOperation[String]("getOneResourceKey")
      summary "Returns a key/cert for this resource"
      description """Returns the signing public key/cert with the specified keyid for this resource. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources/:resource/keys/:keyid", operation(getOneResourceKey)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TResource(compositeId),Access.READ)
    val resp = response
    db.run(ResourceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" result: "+list.size)
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

  // =========== PUT /orgs/{orgid}/resources/{resource}/keys/{keyid} ===============================
  val putResourceKey =
    (apiOperation[ApiResponse]("putResourceKey")
      summary "Adds/updates a key/cert for the resource"
      description """Adds a new signing public key/cert, or updates an existing key/cert, for this resource. This can only be run by the resource owning user. Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[String],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putResourceKey2 = (apiOperation[String]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/resources/:resource/keys/:keyid", operation(putResourceKey)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val keyReq = PutResourceKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutResourceKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toResourceKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/resources/"+resource+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "key added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "key '"+keyId+"' for resource '"+compositeId+"' not inserted or updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "key '"+keyId+"' for resource '"+compositeId+"' not inserted or updated: "+t.getMessage)
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/resources/{resource}/keys ===============================
  val deleteResourceAllKey =
    (apiOperation[ApiResponse]("deleteResourceAllKey")
      summary "Deletes all keys of a resource"
      description "Deletes all of the current keys/certs for this resource. This can only be run by the resource owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/resources/:resource/keys", operation(deleteResourceAllKey)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val resp = response
    db.run(ResourceKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /resources/"+resource+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "resource keys deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "no keys for resource '"+compositeId+"' found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "keys for resource '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/resources/{resource}/keys/{keyid} ===============================
  val deleteResourceKey =
    (apiOperation[ApiResponse]("deleteResourceKey")
      summary "Deletes a key of a resource"
      description "Deletes a key/cert for this resource. This can only be run by the resource owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/resources/:resource/keys/:keyid", operation(deleteResourceKey)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val resp = response
    db.run(ResourceKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /resources/"+resource+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "resource key deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' for resource '"+compositeId+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "key '"+keyId+"' for resource '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/resources/{resource}/auths ================================ */
  val getResourceAuths =
    (apiOperation[List[ResourceAuth]]("getResourceAuths")
      summary "Returns all auth tokens for this resource"
      description """Returns all the authentication tokens for this resource. Can be run by any credentials able to view the resource."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("authid", DataType.String, Option[String]("ID of the auth."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources/:resource/auths", operation(getResourceAuths)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    authenticate().authorizeTo(TResource(compositeId),Access.READ)
    val resp = response
    db.run(ResourceAuthsTQ.getAuths(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/resources/"+resource+"/auths result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/resources/"+id+"/auths result: "+list.toString)
      val listSorted = list.sortWith(_.authId < _.authId)
      if (listSorted.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      listSorted.map(_.toResourceAuth)
    })
  })

  /* ====== GET /orgs/{orgid}/resources/{resource}/auths/{authid} ================================ */
  val getOneResourceAuth =
    (apiOperation[ResourceAuth]("getOneResourceAuth")
      summary "Returns a auth token for this resource"
      description """Returns the  authentication token with the specified authid for this resource. Can be run by any credentials able to view the resource."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("authid", DataType.String, Option[String]("ID of the auth."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/resources/:resource/auths/:authid", operation(getOneResourceAuth)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val authId = try { params("authid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "authid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TResource(compositeId),Access.READ)
    val resp = response
    db.run(ResourceAuthsTQ.getAuth(compositeId, authId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" result: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.OK)
        list.head.toResourceAuth
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        list
      }
    })
  })

  // =========== POST /orgs/{orgid}/resources/{resource}/auths ===============================
  val postResourceAuth =
    (apiOperation[ApiResponse]("postResourceAuth")
      summary "Adds a auth token for the resource"
      description """Adds a new authentication token for this resource. As an optimization, if the auth resource already exists, this method will just update that lastupdated field. This can only be run by the resource owning user."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutResourceAuthRequest],
        Option[String]("Auth object that needs to be added or updated in the exchange. Current supported values for 'username' are: ?????."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postResourceAuth2 = (apiOperation[PostPutResourceAuthRequest]("postAuth2") summary("a") description("a"))  // for some bizarre reason, the PostAuthsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/resources/:resource/auths", operation(postResourceAuth)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val authId = 0      // the db will choose a new id on insert
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val authIdReq = try { parse(request.body).extract[PostPutResourceAuthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    authIdReq.validate(authId)
    val resp = response
    db.run(authIdReq.getDupAuth(compositeId).result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/resources"+resource+"/auths find duplicate: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) ResourceAuthsTQ.getLastUpdatedAction(compositeId, v.head.authId).asTry    // there was a duplicate entry, so just update its lastUpdated field
          else authIdReq.toResourceAuthRow(compositeId, authId).insert.asTry     // no duplicate entry so add the one they gave us
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/resources/"+resource+"/auths result: "+xs.toString)
      xs match {
        case Success(n) => val num = n.toString.toInt     // num is either the id that was added, or (in the dup case) the number of rows that were updated (0 or 1)
          resp.setStatus(HttpCode.POST_OK)
          num match {
            case 0 => ApiResponse(ApiResponseType.OK, "duplicate auth resource already exists")    // we don't expect this, but it is possible, but only means that the lastUpdated field didn't get updated
            case 1 => ApiResponse(ApiResponseType.OK, "auth resource updated")    //todo: this can be 2 cases i dont know how to distinguish between: A) the 1st time anyone added a auth, or B) a dup was found and we updated it
            case _ => ApiResponse(ApiResponseType.OK, "auth "+num+" added")    // we did not find a dup, so this is the auth id that was added
          }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "authId '"+authId+"' for resource '"+compositeId+"' not inserted: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "authId '"+authId+"' for resource '"+compositeId+"' not inserted: "+t.getMessage)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/resources/{resource}/auths/{authid} ===============================
  val putResourceAuth =
    (apiOperation[ApiResponse]("putResourceAuth")
      summary "Updates a docker image token for the resource"
      description """Updates an existing docker image authentication token for this resource. This can only be run by the resource owning user."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("authid", DataType.String, Option[String]("ID of the docker token."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutResourceAuthRequest],
        Option[String]("Auth object that needs to be added to, or updated in, the exchange. Current supported values for 'username' are: 'token' (default) or 'iamapikey'."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putResourceAuth2 = (apiOperation[PostPutResourceAuthRequest]("putAuth2") summary("a") description("a"))  // for some bizarre reason, the PutAuthsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/resources/:resource/auths/:authid", operation(putResourceAuth)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val authId = try { params("authid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "authid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val authIdReq = try { parse(request.body).extract[PostPutResourceAuthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    authIdReq.validate(authId)
    val resp = response
    db.run(authIdReq.toResourceAuthRow(compositeId, authId).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/resources/"+resource+"/auths/"+authId+" result: "+xs.toString)
      xs match {
        case Success(n) => val numUpdated = n.toString.toInt     // n is an AnyRef so we have to do this to get it to an int
          if (numUpdated > 0) {
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, "auth "+authId+" updated")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.OK, "auth "+authId+" not found")
          }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "authId '"+authId+"' for resource '"+compositeId+"' not updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "authId '"+authId+"' for resource '"+compositeId+"' not updated: "+t.getMessage)
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/resources/{resource}/auths ===============================
  val deleteResourceAllAuth =
    (apiOperation[ApiResponse]("deleteResourceAllAuth")
      summary "Deletes all docker image auth tokens of a resource"
      description "Deletes all of the current docker image auth tokens for this resource. This can only be run by the resource owning user."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/resources/:resource/auths", operation(deleteResourceAllAuth)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val resp = response
    db.run(ResourceAuthsTQ.getAuths(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /resources/"+resource+"/auths result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "resource auths deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "no auths for resource '"+compositeId+"' found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "auths for resource '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/resources/{resource}/auths/{authid} ===============================
  val deleteResourceAuth =
    (apiOperation[ApiResponse]("deleteResourceAuth")
      summary "Deletes a docker image auth token of a resource"
      description "Deletes a docker image auth token for this resource. This can only be run by the resource owning user."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("resource", DataType.String, Option[String]("Resource id."), paramType=ParamType.Path),
      Parameter("authid", DataType.String, Option[String]("ID of the auths."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/resources/:resource/auths/:authid", operation(deleteResourceAuth)) ({
    val orgid = params("orgid")
    val resource = params("resource")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,resource).toString
    val authId = try { params("authid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "authid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TResource(compositeId),Access.WRITE)
    val resp = response
    db.run(ResourceAuthsTQ.getAuth(compositeId,authId).delete.asTry).map({ xs =>
      logger.debug("DELETE /resources/"+resource+"/auths/"+authId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "resource auths deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "auths '"+authId+"' for resource '"+compositeId+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "auths '"+authId+"' for resource '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

}