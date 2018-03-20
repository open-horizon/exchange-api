/** Services routes for all of the /orgs/{orgid}/services api methods. */
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
import scala.collection.mutable.{ListBuffer, HashMap => MutableHashMap}
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/services routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/services */
case class GetServicesResponse(services: Map[String,Service], lastIndex: Int)
case class GetServiceAttributeResponse(attribute: String, value: String)

object SharableVals extends Enumeration {
  type SharableVals = Value
  val EXCLUSIVE = Value("exclusive")
  val SINGLE = Value("single")
  val MULTIPLE = Value("multiple")
}

/** Input format for POST /orgs/{orgid}/services or PUT /orgs/{orgid}/services/<service-id> */
case class PostPutServiceRequest(label: String, description: String, public: Boolean, url: String, version: String, arch: String, sharable: String, matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]], deployment: String, deploymentSignature: String, imageStore: Option[Map[String,Any]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate(orgid: String, serviceId: String) = {
    // Currently we do not want to force that the url is a valid URL
    //try { new URL(url) }
    //catch { case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "url is not valid URL format.")) }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "version '"+version+"' is not valid version format."))
    if (arch == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "arch can not be empty."))

    // We enforce that the attributes equal the existing id for PUT, because even if they change the attribute, the id would not get updated correctly
    if (serviceId != null && serviceId != "" && formId(orgid) != serviceId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the service id specified in the URL does not match the url, version, and arch in the body."))

    val allSharableVals = SharableVals.values.map(_.toString)
    if (sharable == "" || !allSharableVals.contains(sharable)) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value '"+sharable+"' for the sharable attribute."))

    // Check for requiring a service that is a different arch than this service
    for (rs <- requiredServices.getOrElse(List())) {
      if (rs.arch != arch) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "required service '"+rs.url+"' has arch '"+rs.arch+"', which is different than this service's arch '"+arch+"'"))
    }

    // Check that it is signed
    if (deployment != "" && deploymentSignature == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "this service definition does not appear to be signed."))
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: DBIO[Vector[Int]] = {
    if (requiredServices.isEmpty || requiredServices.get.isEmpty) return DBIO.successful(Vector())
    val actions = ListBuffer[DBIO[Int]]()
    for (m <- requiredServices.get) {
      val svcId = ServicesTQ.formId(m.org, m.url, m.version, m.arch)     // need to wildcard version, because it is an osgi version range
      actions += ServicesTQ.getService(svcId).length.result
    }
    return DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence because that returns query values
  }

  def formId(orgid: String) = ServicesTQ.formId(orgid, url, version, arch)

  def toServiceRow(service: String, orgid: String, owner: String) = ServiceRow(service, orgid, owner, label, description, public, url, version, arch, sharable, write(matchHardware), write(requiredServices), write(userInput), deployment, deploymentSignature, write(imageStore), ApiTime.nowUTC)
}

case class PatchServiceRequest(label: Option[String], description: Option[String], public: Option[Boolean], url: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]], deployment: Option[String], deploymentSignature: Option[String], imageStore: Option[Map[String,Any]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the service, and the attribute name being updated. */
  def getDbUpdate(service: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this service
    label match { case Some(lab) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.label,d.lastUpdated)).update((service, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.description,d.lastUpdated)).update((service, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.public,d.lastUpdated)).update((service, pub, lastUpdated)), "public"); case _ => ; }
    url match { case Some(u) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.url,d.lastUpdated)).update((service, u, lastUpdated)), "url"); case _ => ; }
    version match { case Some(ver) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.version,d.lastUpdated)).update((service, ver, lastUpdated)), "version"); case _ => ; }
    arch match { case Some(ar) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.arch,d.lastUpdated)).update((service, ar, lastUpdated)), "arch"); case _ => ; }
    sharable match { case Some(share) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.sharable,d.lastUpdated)).update((service, share, lastUpdated)), "sharable"); case _ => ; }
    matchHardware match { case Some(mh) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.matchHardware,d.lastUpdated)).update((service, write(mh), lastUpdated)), "matchHardware"); case _ => ; }
    requiredServices match { case Some(rs) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.requiredServices,d.lastUpdated)).update((service, write(rs), lastUpdated)), "requiredServices"); case _ => ; }
    userInput match { case Some(ui) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.userInput,d.lastUpdated)).update((service, write(ui), lastUpdated)), "userInput"); case _ => ; }
    deployment match { case Some(dep) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.deployment,d.lastUpdated)).update((service, dep, lastUpdated)), "deployment"); case _ => ; }
    deploymentSignature match { case Some(depsig) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.deploymentSignature,d.lastUpdated)).update((service, depsig, lastUpdated)), "deploymentSignature"); case _ => ; }
    imageStore match { case Some(p) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.imageStore,d.lastUpdated)).update((service, write(p), lastUpdated)), "imageStore"); case _ => ; }
    return (null, null)
  }
}


/** Input format for PUT /orgs/{orgid}/services/{service}/keys/<key-id> */
case class PutServiceKeyRequest(key: String) {
  def toServiceKey = ServiceKey(key, ApiTime.nowUTC)
  def toServiceKeyRow(serviceId: String, keyId: String) = ServiceKeyRow(keyId, serviceId, key, ApiTime.nowUTC)
  def validate(keyId: String) = {
    //if (keyId != formId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the key id should be in the form keyOrgid_key"))
  }
}



/** Implementation for all of the /orgs/{orgid}/services routes */
trait ServiceRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats

  // ====== GET /orgs/{orgid}/services ================================
  val getServices =
    (apiOperation[GetServicesResponse]("getServices")
      summary("Returns all services")
      description("""Returns all service definitions in the exchange DB. Can be run by any user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("owner", DataType.String, Option[String]("Filter results to only include services with this owner (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("public", DataType.String, Option[String]("Filter results to only include services with this public setting"), paramType=ParamType.Query, required=false),
        Parameter("url", DataType.String, Option[String]("Filter results to only include services with this url (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("version", DataType.String, Option[String]("Filter results to only include services with this version (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("arch", DataType.String, Option[String]("Filter results to only include services with this arch (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false),
        Parameter("requiredurl", DataType.String, Option[String]("Filter results to only include services that use this service with this url (can include % for wildcard - the URL encoding for % is %25)"), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/services", operation(getServices)) ({
  //get("/orgs/:orgid/services") ({
    val orgid = params("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TService(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    var q = ServicesTQ.getAllServices(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("public").foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
    params.get("url").foreach(url => { if (url.contains("%")) q = q.filter(_.url like url) else q = q.filter(_.url === url) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    // We are cheating a little on this one because the whole requiredServices structure is serialized into a json string when put in the db, so it has a string value like
    // [{"url":"https://bluehorizon.network/services/rtlsdr","version":"1.0.0","arch":"amd64"}]. But we can still match on the url.
    params.get("requiredurl").foreach(requrl => {
      val requrl2 = "%\"url\":\"" + requrl + "\"%"
      q = q.filter(_.requiredServices like requrl2)
    })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services result size: "+list.size)
      logger.trace("GET /orgs/"+orgid+"/services result: "+list.toString())
      val services = new MutableHashMap[String,Service]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || a.public || ident.isSuperUser || ident.isMultiTenantAgbot) services.put(a.service, a.toService)
      else resp.setStatus(HttpCode.NOT_FOUND)
      GetServicesResponse(services.toMap, 0)
    })
  })

  // ====== GET /orgs/{orgid}/services/{service} ================================
  val getOneService =
    (apiOperation[GetServicesResponse]("getOneService")
      summary("Returns a service")
      description("""Returns the service with the specified id in the exchange DB. Can be run by a user, node, or agbot.""")
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("attribute", DataType.String, Option[String]("Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire service resource will be returned."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/services/:service", operation(getOneService)) ({
  //get("/orgs/:orgid/services/:service") ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    credsAndLog().authenticate().authorizeTo(TService(service),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the service
        val q = ServicesTQ.getAttribute(service, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Service attribute name '"+attribute+"' is not an attribute of the service resource."))
        db.run(q.result).map({ list =>
          logger.trace("GET /orgs/"+orgid+"/services/"+bareService+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            GetServiceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "not found")
          }
        })

      case None => ;  // Return the whole service resource
        db.run(ServicesTQ.getService(service).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/services/"+bareService+" result: "+list.toString)
          val services = new MutableHashMap[String,Service]
          if (list.nonEmpty) for (a <- list) services.put(a.service, a.toService)
          else resp.setStatus(HttpCode.NOT_FOUND)
          GetServicesResponse(services.toMap, 0)
        })
    }
  })

  // =========== POST /orgs/{orgid}/services ===============================
  val postServices =
    (apiOperation[ApiResponse]("postServices")
      summary "Adds a service"
      description """Creates a service resource. A service resource contains the metadata that Horizon needs to deploy the docker images that implement this service. A service can either be an edge application, or a lower level edge service that provides access to sensors or reusable features. The service can require 1 or more other services that Horizon should also deploy when deploying this service. If public is set to true, the service can be shared across organizations. This can only be called by a user. The **request body** structure:

```
// (remove all of the comments like this before using)
{
  "label": "Location for amd64",     // this will be displayed in the node registration UI
  "description": "blah blah",
  "public": true,       // whether or not it can be viewed by other organizations
  "url": "https://bluehorizon.network/services/location",   // the unique identifier of this service
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "single",   // if multiple services require this service, how many instances are deployed: "exclusive", "single", "multiple"
  "matchHardware": {},    // reserved for future use, can be omitted (will be hints to the node about how to tell if it has the physical sensors required by this service)
  // The other services this service requires. (The other services must exist in the exchange before creating this service.)
  "requiredServices": [
    {
      "url": "https://bluehorizon.network/services/gps",
      "org": "myorg",
      "version": "[1.0.0,INFINITY)",     // an OSGI-formatted version range
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",   // or: "int", "float", "boolean", or "list of strings"
      "defaultValue": "bar"   // if empty then the node owner must provide a value at registration time
    }
  ],
  // Information about how to deploy the docker images for this service
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",     // filled in by the Horizon signing process
  "imageStore": {
    // There could be several different package reference schemes so the schema will be left open. However, the storeType must be set for all cases to discriminate the type of storage being used.
    "storeType": "dockerRegistry" // imageServer and dockerRegistry are the only supported values right now
  }
}
```"""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutServiceRequest],
          Option[String]("Service object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val postServices2 = (apiOperation[PostPutServiceRequest]("postServices2") summary("a") description("a"))  // for some bizarre reason, the PostServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/services", operation(postServices)) ({
  //post("/orgs/:orgid/services") ({
    val orgid = params("orgid")
    val ident = credsAndLog().authenticate().authorizeTo(TService(OrgAndId(orgid,"").toString),Access.CREATE)
    val serviceReq = try { parse(request.body).extract[PostPutServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    serviceReq.validate(orgid, null)
    val service = serviceReq.formId(orgid)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update services, so owner will never be blank
    val resp = response

    // Make a list of service wildcarded searches for the required services. This can match more services than we need, we'll do exact matches later.
    val svcIds = serviceReq.requiredServices.getOrElse(List()).map( s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
    val action = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
      else {
        // The inner map() and reduceLeft() OR together all of the likes to give to filter()
        ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
      }

    db.run(action.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services requiredServices validation: "+xs.toString)
      xs match {
        case Success(rows) => var invalidIndex = -1
          // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
          // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
          breakable { for ( (svcRef, index) <- serviceReq.requiredServices.getOrElse(List()).zipWithIndex) {
            breakable {
              for ( (orgid,url,version,arch) <- rows ) {
                //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(svcRef.version)) ) break  // we satisfied this requiredService so move on to the next
              }
              invalidIndex = index    // we finished the inner loop but did not find a service that satisfied the requirement
            }     //  if we found a service that satisfies the requirement, it breaks to this line
            if (invalidIndex >= 0) break    // a requiredService was not satisfied, so break out of the outer loop and return an error
          } }
          if (invalidIndex < 0) ServicesTQ.getNumOwned(owner).result.asTry    // we are good, move on to the next step
          else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service in requiredServices does not exist in the exchange")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services num owned by "+owner+": "+xs)
      xs match {
        case Success(num) => val numOwned = num
          val maxServices = ExchConfig.getInt("api.limits.maxServices")
          if (maxServices == 0 || numOwned <= maxServices) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
            serviceReq.toServiceRow(service, orgid, owner).insert.asTry
          }
          else DBIO.failed(new Throwable("Access Denied: you are over the limit of "+maxServices+ " services")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.services.putOwner(service, owner)     // currently only users are allowed to update service resources, so owner should never be blank
          AuthCache.services.putIsPublic(service, serviceReq.public)
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, "service '"+service+"' created")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "service '" + service + "' not created: " + t.getMessage)
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, "service '" + service + "' already exists: " + t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "service '"+service+"' not created: "+t.getMessage)
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/services/{service} ===============================
  val putServices =
    (apiOperation[ApiResponse]("putServices")
      summary "Updates a service"
      description """Does a full replace of an existing service. This can only be called by the user that originally created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutServiceRequest],
          Option[String]("Service object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putServices2 = (apiOperation[PostPutServiceRequest]("putServices2") summary("a") description("a"))  // for some bizarre reason, the PutServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service", operation(putServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    val ident = credsAndLog().authenticate().authorizeTo(TService(service),Access.WRITE)
    val serviceReq = try { parse(request.body).extract[PostPutServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }
    serviceReq.validate(orgid, service)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }     // currently only users are allowed to update service resources, so owner should never be blank
    val resp = response

    // Make a list of service wildcarded searches for the required services. This can match more services than we need, we'll do exact matches later.
    val svcIds = serviceReq.requiredServices.getOrElse(List()).map( m => ServicesTQ.formId(m.org, m.url, "%", m.arch) )
    val action = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
    else {
      // The inner map() and reduceLeft() OR together all of the likes to give to filter()
      ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
    }

    db.run(action.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services apiSpec validation: "+xs.toString)
      xs match {
        case Success(rows) => var invalidIndex = -1
          // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
          // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
          breakable { for ( (svcRef, index) <- serviceReq.requiredServices.getOrElse(List()).zipWithIndex) {
            breakable {
              for ( (orgid,specRef,version,arch) <- rows ) {
                //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                if (specRef == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(svcRef.version)) ) break  // we satisfied this apiSpec requirement so move on to the next
              }
              invalidIndex = index    // we finished the inner loop but did not find a service that satisfied the requirement
            }     //  if we found a service that satisfies the requirment, it breaks to this line
            if (invalidIndex >= 0) break    // an requiredService was not satisfied, so break out and return an error
          } }
          if (invalidIndex < 0) serviceReq.toServiceRow(service, orgid, owner).update.asTry    // we are good, move on to the next step
          else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service in requiredServices does not exist in the exchange")).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/services/"+bareService+" result: "+xs.toString)
      xs match {
        case Success(n) => try {
            val numUpdated = n.toString.toInt     // i think n is an AnyRef so we have to do this to get it to an int
            if (numUpdated > 0) {
              if (owner != "") AuthCache.services.putOwner(service, owner)     // currently only users are allowed to update service resources, so owner should never be blank
              AuthCache.services.putIsPublic(service, serviceReq.public)
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "service updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "service '"+service+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "service '"+service+"' not updated: "+e) }    // the specific exception is NumberFormatException
        case Failure(t) => resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "service '"+service+"' not updated: "+t.getMessage)
      }
    })
  })

  // =========== PATCH /orgs/{orgid}/services/{service} ===============================
  val patchServices =
    (apiOperation[Map[String,String]]("patchServices")
      summary "Updates 1 attribute of a service"
      description """Updates one attribute of a service in the exchange DB. This can only be called by the user that originally created this service resource."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PatchServiceRequest],
          Option[String]("Partial service object that contains 1 attribute to be updated in this service. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val patchServices2 = (apiOperation[PatchServiceRequest]("patchServices2") summary("a") description("a"))  // for some bizarre reason, the PatchServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/services/:service", operation(patchServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    credsAndLog().authenticate().authorizeTo(TService(service),Access.WRITE)
    val serviceReq = try { parse(request.body).extract[PatchServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    logger.trace("PATCH /orgs/"+orgid+"/services/"+bareService+" input: "+serviceReq.toString)
    val resp = response
    val (action, attrName) = serviceReq.getDbUpdate(service, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "no valid service attribute specified"))
    if (attrName == "url" || attrName == "version" || attrName == "arch") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "patching attributes 'url', 'version', and 'arch' are not allowed (because the id would not match). To change those attributes you must delete the resource and recreate it."))
    if (attrName == "sharable") {
      val allSharableVals = SharableVals.values.map(_.toString)
      if (!allSharableVals.contains(serviceReq.sharable.getOrElse(""))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "invalid value '" + serviceReq.sharable.getOrElse("") + "' for the sharable attribute."))
    }
    db.run(action.transactionally.asTry).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/services/"+bareService+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (attrName == "public") AuthCache.services.putIsPublic(service, serviceReq.public.getOrElse(false))
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, "attribute '"+attrName+"' of service '"+service+"' updated")
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, "service '"+service+"' not found")
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, "Unexpected result from update: "+e) }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "service '"+service+"' not updated: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service} ===============================
  val deleteServices =
    (apiOperation[ApiResponse]("deleteServices")
      summary "Deletes a service"
      description "Deletes a service from the exchange DB. Can only be run by the owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
        )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/services/:service", operation(deleteServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    credsAndLog().authenticate().authorizeTo(TService(service),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(ServicesTQ.getService(service).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/services/"+bareService+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.services.removeOwner(service)
            AuthCache.services.removeIsPublic(service)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, "service deleted")
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, "service '"+service+"' not found")
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "service '"+service+"' not deleted: "+t.toString)
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/keys ================================ */
  val getServiceKeys =
    (apiOperation[List[String]]("getServiceKeys")
      summary "Returns all keys/certs for this service"
      description """Returns all the signing public keys/certs for this service. Can be run by any credentials able to view the service."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/services/:service/keys", operation(getServiceKeys)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    credsAndLog().authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceKeysTQ.getKeys(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/services/"+id+"/keys result: "+list.toString)
      if (list.isEmpty) resp.setStatus(HttpCode.NOT_FOUND)
      list.map(_.keyId)
    })
  })

  /* ====== GET /orgs/{orgid}/services/{service}/keys/{keyid} ================================ */
  val getOneServiceKey =
    (apiOperation[String]("getOneServiceKey")
      summary "Returns a key/cert for this service"
      description """Returns the signing public key/cert with the specified keyid for this service. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the service."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  get("/orgs/:orgid/services/:service/keys/:keyid", operation(getOneServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+list.size)
      if (list.nonEmpty) {
        // Return the raw key, not json
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

  // =========== PUT /orgs/{orgid}/services/{service}/keys/{keyid} ===============================
  val putServiceKey =
    (apiOperation[ApiResponse]("putServiceKey")
      summary "Adds/updates a key/cert for the service"
      description """Adds a new signing public key/cert, or updates an existing key/cert, for this service. This can only be run by the service owning user. Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PutServiceKeyRequest],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,"created/updated"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.BAD_INPUT,"bad input"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )
  val putServiceKey2 = (apiOperation[PutServiceKeyRequest]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service/keys/:keyid", operation(putServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val keyReq = PutServiceKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutServiceKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toServiceKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, "key added or updated")
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, "key '"+keyId+"' for service '"+compositeId+"' not inserted or updated: "+t.getMessage)
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, "key '"+keyId+"' for service '"+compositeId+"' not inserted or updated: "+t.getMessage)
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service}/keys ===============================
  val deleteServiceAllKey =
    (apiOperation[ApiResponse]("deleteServiceAllKey")
      summary "Deletes all keys of a service"
      description "Deletes all of the current keys/certs for this service. This can only be run by the service owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/services/:service/keys", operation(deleteServiceAllKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    credsAndLog().authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "service keys deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "no keys for service '"+compositeId+"' found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "keys for service '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service}/keys/{keyid} ===============================
  val deleteServiceKey =
    (apiOperation[ApiResponse]("deleteServiceKey")
      summary "Deletes a key of a service"
      description "Deletes a key/cert for this service. This can only be run by the service owning user."
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("microservice", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,"deleted"), ResponseMessage(HttpCode.BADCREDS,"invalid credentials"), ResponseMessage(HttpCode.ACCESS_DENIED,"access denied"), ResponseMessage(HttpCode.NOT_FOUND,"not found"))
      )

  delete("/orgs/:orgid/services/:service/keys/:keyid", operation(deleteServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    credsAndLog().authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, "service key deleted")
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, "key '"+keyId+"' for service '"+compositeId+"' not found")
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, "key '"+keyId+"' for service '"+compositeId+"' not deleted: "+t.toString)
      }
    })
  })

}