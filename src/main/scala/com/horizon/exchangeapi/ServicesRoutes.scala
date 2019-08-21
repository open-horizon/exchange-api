/** Services routes for all of the /orgs/{orgid}/services api methods. */
package com.horizon.exchangeapi

import com.horizon.exchangeapi.tables._
import java.net.{MalformedURLException, URL}

import com.osinka.i18n.{Lang, Messages}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.write
import org.scalatra._
import org.scalatra.swagger._
import org.slf4j._
import slick.jdbc.PostgresProfile.api._

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
  val SINGLE = Value("single")    // this is being replaced by singleton
  val SINGLETON = Value("singleton")
  val MULTIPLE = Value("multiple")
}

/** Input format for POST /orgs/{orgid}/services or PUT /orgs/{orgid}/services/<service-id> */
case class PostPutServiceRequest(label: String, description: Option[String], public: Boolean, documentation: Option[String], url: String, version: String, arch: String, sharable: String, matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]], deployment: String, deploymentSignature: String, imageStore: Option[Map[String,Any]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  implicit val userLang = Lang("en")
  def validate(orgid: String, serviceId: String) = {
    // Ensure that the documentation field is a valid URL
    if (documentation.getOrElse("") != "") {
      try { new URL(documentation.getOrElse("")) }
      catch { case _: MalformedURLException => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("documentation.field.not.valid.url"))) }
    }

    if (!Version(version).isValid) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("version.not.valid.format", version)))
    if (arch == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("arch.cannot.be.empty")))

    // We enforce that the attributes equal the existing id for PUT, because even if they change the attribute, the id would not get updated correctly
    if (serviceId != null && serviceId != "" && formId(orgid) != serviceId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.id.does.not.match")))

    val allSharableVals = SharableVals.values.map(_.toString)
    if (sharable == "" || !allSharableVals.contains(sharable)) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("invalid.sharable.value", sharable)))

    // Check for requiring a service that is a different arch than this service
    for (rs <- requiredServices.getOrElse(List())) {
      if(rs.versionRange.isEmpty && rs.version.isEmpty){halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("no.version.range.in.req.service", rs.url)))}
      if (rs.arch != arch) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("req.service.has.wrong.arch", rs.url, rs.arch, arch)))
    }

    // Check that it is signed
    if (deployment != "" && deploymentSignature == "") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.def.not.signed")))
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: DBIO[Vector[Int]] = {
    if (requiredServices.isEmpty || requiredServices.get.isEmpty) return DBIO.successful(Vector())
    val actions = ListBuffer[DBIO[Int]]()
    for (m <- requiredServices.get) {
      val finalVersionRange = if(m.versionRange.isEmpty) m.version.getOrElse("") else m.versionRange.getOrElse("")
      val svcId = ServicesTQ.formId(m.org, m.url, finalVersionRange, m.arch)     // need to wildcard version, because it is an osgi version range
      actions += ServicesTQ.getService(svcId).length.result
    }
    return DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence because that returns query values
  }

  def formId(orgid: String) = ServicesTQ.formId(orgid, url, version, arch)

  def toServiceRow(service: String, orgid: String, owner: String) = ServiceRow(service, orgid, owner, label, description.getOrElse(label), public, documentation.getOrElse(""), url, version, arch, sharable, write(matchHardware), write(requiredServices), write(userInput), deployment, deploymentSignature, write(imageStore), ApiTime.nowUTC)
}

case class PatchServiceRequest(label: Option[String], description: Option[String], public: Option[Boolean], documentation: Option[String], url: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]], deployment: Option[String], deploymentSignature: Option[String], imageStore: Option[Map[String,Any]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  /** Returns a tuple of the db action to update parts of the service, and the attribute name being updated. */
  def getDbUpdate(service: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated = ApiTime.nowUTC
    // find the 1st attribute that was specified in the body and create a db action to update it for this service
    label match { case Some(lab) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.label,d.lastUpdated)).update((service, lab, lastUpdated)), "label"); case _ => ; }
    description match { case Some(desc) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.description,d.lastUpdated)).update((service, desc, lastUpdated)), "description"); case _ => ; }
    public match { case Some(pub) => return ((for { d <- ServicesTQ.rows if d.service === service } yield (d.service,d.public,d.lastUpdated)).update((service, pub, lastUpdated)), "public"); case _ => ; }
    documentation match { case Some(doc) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.documentation,d.lastUpdated)).update((service, doc, lastUpdated)), "documentation"); case _ => ; }
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


case class PutServicePolicyRequest(properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def validate() = {
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of string", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("property.type.must.be", p.`type`.get, validTypes.mkString(", "))(Lang("en"))))
      }
    }
  }

  def toServicePolicyRow(serviceId: String) = ServicePolicyRow(serviceId, write(properties), write(constraints), ApiTime.nowUTC)
}


/** Input format for PUT /orgs/{orgid}/services/{service}/keys/<key-id> */
case class PutServiceKeyRequest(key: String) {
  def toServiceKey = ServiceKey(key, ApiTime.nowUTC)
  def toServiceKeyRow(serviceId: String, keyId: String) = ServiceKeyRow(keyId, serviceId, key, ApiTime.nowUTC)
  def validate(keyId: String) = {
    //if (keyId != formId) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "the key id should be in the form keyOrgid_key"))
  }
}


/** Response for GET /orgs/{orgid}/agbots/{id}/msgs */
//case class GetServiceDockAuthResponse(dockauths: List[ServiceDockAuth], lastIndex: Int)

/** Input format for POST /orgs/{orgid}/services/{service}/dockauths or PUT /orgs/{orgid}/services/{service}/dockauths/{dockauthid} */
case class PostPutServiceDockAuthRequest(registry: String, username: Option[String], token: String) {
  def toServiceDockAuthRow(serviceId: String, dockAuthId: Int) = ServiceDockAuthRow(dockAuthId, serviceId, registry, username.getOrElse("token"), token, ApiTime.nowUTC)
  def validate(dockAuthId: Int) = { }
  def getDupDockAuth(serviceId: String) = ServiceDockAuthsTQ.getDupDockAuth(serviceId, registry, username.getOrElse("token"), token)
}



/** Implementation for all of the /orgs/{orgid}/services routes */
trait ServiceRoutes extends ScalatraBase with FutureSupport with SwaggerSupport with AuthenticationSupport {
  def db: Database      // get access to the db object in ExchangeApiApp
  def logger: Logger    // get access to the logger object in ExchangeApiApp
  protected implicit def jsonFormats: Formats
  override implicit val userLang = Lang("en")

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
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services", operation(getServices)) ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TService(OrgAndId(orgid,"*").toString),Access.READ)
    val resp = response
    var q = ServicesTQ.getAllServices(orgid)
    // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
    params.get("owner").foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
    params.get("public").foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
    params.get("url").foreach(url => { if (url.contains("%")) q = q.filter(_.url like url) else q = q.filter(_.url === url) })
    params.get("version").foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
    params.get("arch").foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })

    // We are cheating a little on this one because the whole requiredServices structure is serialized into a json string when put in the db, so it has a string value like
    // [{"url":"mydomain.com.rtlsdr","version":"1.0.0","arch":"amd64"}]. But we can still match on the url.
    params.get("requiredurl").foreach(requrl => {
      val requrl2 = "%\"url\":\"" + requrl + "\"%"
      q = q.filter(_.requiredServices like requrl2)
    })

    db.run(q.result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/services result: "+list.toString())
      val services = new MutableHashMap[String,Service]
      if (list.nonEmpty) for (a <- list) if (ident.getOrg == a.orgid || a.public || ident.isSuperUser || ident.isMultiTenantAgbot) services.put(a.service, a.toService)
      if (services.nonEmpty) resp.setStatus(HttpCode.OK)
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
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service", operation(getOneService)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.READ)
    val resp = response
    params.get("attribute") match {
      case Some(attribute) => ; // Only returning 1 attr of the service
        val q = ServicesTQ.getAttribute(service, attribute)       // get the proper db query for this attribute
        if (q == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("attribute.not.part.of.service", attribute)))
        db.run(q.result).map({ list =>
          //logger.trace("GET /orgs/"+orgid+"/services/"+bareService+" attribute result: "+list.toString)
          if (list.nonEmpty) {
            resp.setStatus(HttpCode.OK)
            GetServiceAttributeResponse(attribute, list.head.toString)
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, Messages("not.found"))
          }
        })

      case None => ;  // Return the whole service resource
        db.run(ServicesTQ.getService(service).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/services/"+bareService+" result: "+list.toString)
          val services = new MutableHashMap[String,Service]
          if (list.nonEmpty) for (a <- list) services.put(a.service, a.toService)
          if (services.nonEmpty) resp.setStatus(HttpCode.OK)
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
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",   // description of what this service does and how to use it
  "url": "github.com.open-horizon.examples.sdr2msghub",   // the unique identifier of this service
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",   // if multiple services require this service, how many instances are deployed: "exclusive", "singleton", "multiple"
  // The other services this service requires. (The other services must exist in the exchange before creating this service.)
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
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
  "imageStore": {      // can be omitted
    "storeType": "dockerRegistry" // dockerRegistry is the only supported value right now, and is the default
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
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val postServices2 = (apiOperation[PostPutServiceRequest]("postServices2") summary("a") description("a"))  // for some bizarre reason, the PostServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/services", operation(postServices)) ({
  //post("/orgs/:orgid/services") ({
    val orgid = params("orgid")
    val ident = authenticate().authorizeTo(TService(OrgAndId(orgid,"").toString),Access.CREATE)
    val serviceReq = try { parse(request.body).extract[PostPutServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }
    serviceReq.validate(orgid, null)
    val service = serviceReq.formId(orgid)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update services, so owner will never be blank
    val resp = response

    // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
    // We'll look for versions within the required ranges in the db access routine below.
    val svcIds = serviceReq.requiredServices.getOrElse(List()).map( s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
    val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
    else {
      // The inner map() and reduceLeft() OR together all of the likes to give to filter()
      ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
    }

    db.run(svcAction.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services requiredServices validation: "+xs.toString)
      xs match {
        case Success(rows) => var invalidIndex = -1
          var invalidSvcRef = ServiceRef("","",Some(""),Some(""),"")
          // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
          // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
          breakable { for ( (svcRef, index) <- serviceReq.requiredServices.getOrElse(List()).zipWithIndex) {
            breakable {
              for ( (orgid,url,version,arch) <- rows ) {
                //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                val finalVersionRange = if(svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange)) ) break  // we satisfied this requiredService so move on to the next
              }
              invalidIndex = index    // we finished the inner loop but did not find a service that satisfied the requirement
              invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
            }     //  if we found a service that satisfies the requirement, it breaks to this line
            if (invalidIndex >= 0) break    // a requiredService was not satisfied, so break out of the outer loop and return an error
          } }
          if (invalidIndex < 0) ServicesTQ.getNumOwned(owner).result.asTry    // we are good, move on to the next step
          else {
            //else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service in requiredServices does not exist in the exchange")).asTry
            val errStr = Messages("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    }).flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services num owned by "+owner+": "+xs)
      xs match {
        case Success(num) => val numOwned = num
          val maxServices = ExchConfig.getInt("api.limits.maxServices")
          if (maxServices == 0 || maxServices >= numOwned) {    // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
            serviceReq.toServiceRow(service, orgid, owner).insert.asTry
          }
          else DBIO.failed(new Throwable(Messages("over.the.limit.of.services", maxServices))).asTry
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services result: "+xs.toString)
      xs match {
        case Success(_) => if (owner != "") AuthCache.services.putOwner(service, owner)     // currently only users are allowed to update service resources, so owner should never be blank
          AuthCache.services.putIsPublic(service, serviceReq.public)
          resp.setStatus(HttpCode.POST_OK)
          ApiResponse(ApiResponseType.OK, Messages("service.created"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.not.created", service, t.getMessage))
        } else if (t.getMessage.contains("duplicate key value violates unique constraint")) {
          resp.setStatus(HttpCode.ALREADY_EXISTS)
          ApiResponse(ApiResponseType.ALREADY_EXISTS, Messages("service.already.exists", service, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.not.created", service, t.getMessage))
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/services/{service} ===============================
  val putServices =
    (apiOperation[ApiResponse]("putServices")
      summary "Updates a service"
      description """Does a full replace of an existing service. See the description of the body fields in the POST method. This can only be called by the user that originally created it."""
      parameters(
        Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of exchange user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[PostPutServiceRequest],
          Option[String]("Service object that needs to be updated in the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val putServices2 = (apiOperation[PostPutServiceRequest]("putServices2") summary("a") description("a"))  // for some bizarre reason, the PutServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service", operation(putServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    val ident = authenticate().authorizeTo(TService(service),Access.WRITE)
    val serviceReq = try { parse(request.body).extract[PostPutServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }
    serviceReq.validate(orgid, service)
    val owner = ident match { case IUser(creds) => creds.id; case _ => "" }     // currently only users are allowed to update service resources, so owner should never be blank
    val resp = response

    // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
    // We'll look for versions within the required ranges in the db access routine below.
    val svcIds = serviceReq.requiredServices.getOrElse(List()).map( m => ServicesTQ.formId(m.org, m.url, "%", m.arch) )
    val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
    else {
      // The inner map() and reduceLeft() OR together all of the likes to give to filter()
      ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
    }

    db.run(svcAction.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services requiredServices validation: "+xs.toString)
      xs match {
        case Success(rows) => var invalidIndex = -1
          var invalidSvcRef = ServiceRef("","",Some(""),Some(""),"")
          // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
          // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
          breakable { for ( (svcRef, index) <- serviceReq.requiredServices.getOrElse(List()).zipWithIndex) {
            breakable {
              for ( (orgid,specRef,version,arch) <- rows ) {
                //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                val finalVersionRange = if(svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                if (specRef == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange)) ) break  // we satisfied this apiSpec requirement so move on to the next
              }
              invalidIndex = index    // we finished the inner loop but did not find a service that satisfied the requirement
              invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
            }     //  if we found a service that satisfies the requirment, it breaks to this line
            if (invalidIndex >= 0) break    // an requiredService was not satisfied, so break out and return an error
          } }
          if (invalidIndex < 0) serviceReq.toServiceRow(service, orgid, owner).update.asTry    // we are good, move on to the next step
          else {
            val errStr = Messages("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
            DBIO.failed(new Throwable(errStr)).asTry
          }
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
              ApiResponse(ApiResponseType.OK, Messages("service.updated"))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.not.found", service))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.not.updated", service, e)) }    // the specific exception is NumberFormatException
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.not.updated", service, t.getMessage))
          //            ApiResponse(ApiResponseType.ACCESS_DENIED, "service '" + service + "' not updated: " + t.getMessage)
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.not.updated", service, t.getMessage))
          //            ApiResponse(ApiResponseType.BAD_INPUT, "service '" + service + "' not updated: " + t.getMessage)
          }
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
          Option[String]("Partial service object that contains 1 attribute to be updated in this service."),
          paramType = ParamType.Body)
        )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val patchServices2 = (apiOperation[PatchServiceRequest]("patchServices2") summary("a") description("a"))  // for some bizarre reason, the PatchServiceRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  patch("/orgs/:orgid/services/:service", operation(patchServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.WRITE)
    val serviceReq = try { parse(request.body).extract[PatchServiceRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }    // the specific exception is MappingException
    //logger.trace("PATCH /orgs/"+orgid+"/services/"+bareService+" input: "+serviceReq.toString)
    val resp = response
    val (action, attrName) = serviceReq.getDbUpdate(service, orgid)
    if (action == null) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("no.valid.service.attr.specified")))
    if (attrName == "url" || attrName == "version" || attrName == "arch") halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("cannot.patch.these.attributes")))
    if (attrName == "sharable") {
      val allSharableVals = SharableVals.values.map(_.toString)
      if (!allSharableVals.contains(serviceReq.sharable.getOrElse(""))) halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("invalid.value.for.sharable.attribute",serviceReq.sharable.getOrElse("") )))
    }

    // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
    // We'll look for versions within the required ranges in the db access routine below.
    val svcIds = if (attrName == "requiredServices") serviceReq.requiredServices.getOrElse(List()).map( s => ServicesTQ.formId(s.org, s.url, "%", s.arch) ) else List()
    val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
    else {
      // The inner map() and reduceLeft() OR together all of the likes to give to filter()
      ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
    }

    // First check that the requiredServices exist (if that is not what they are patching, this is a noop)
    //todo: add a step to update the owner, if different
    db.run(svcAction.transactionally.asTry.flatMap({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/services requiredServices validation: "+xs.toString)
      xs match {
        case Success(rows) => var invalidIndex = -1
          var invalidSvcRef = ServiceRef("","",Some(""),Some(""),"")
          // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
          // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
          breakable { for ( (svcRef, index) <- serviceReq.requiredServices.getOrElse(List()).zipWithIndex) {
            breakable {
              for ( (orgid,url,version,arch) <- rows ) {
                //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                val finalVersionRange = if(svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange)) ) break  // we satisfied this requiredService so move on to the next
              }
              invalidIndex = index    // we finished the inner loop but did not find a service that satisfied the requirement
              invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version,svcRef.versionRange, svcRef.arch)
            }     //  if we found a service that satisfies the requirement, it breaks to this line
            if (invalidIndex >= 0) break    // a requiredService was not satisfied, so break out of the outer loop and return an error
          } }
          if (invalidIndex < 0) action.transactionally.asTry    // we are good, move on to the real patch action
          else {
            val errStr = Messages("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
            DBIO.failed(new Throwable(errStr)).asTry
          }
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("PATCH /orgs/"+orgid+"/services/"+bareService+" result: "+xs.toString)
      xs match {
        case Success(v) => try {
            val numUpdated = v.toString.toInt     // v comes to us as type Any
            if (numUpdated > 0) {        // there were no db errors, but determine if it actually found it or not
              if (attrName == "public") AuthCache.services.putIsPublic(service, serviceReq.public.getOrElse(false))
              resp.setStatus(HttpCode.PUT_OK)
              ApiResponse(ApiResponseType.OK, Messages("service.attr.updated", attrName, service))
            } else {
              resp.setStatus(HttpCode.NOT_FOUND)
              ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.not.found", service))
            }
          } catch { case e: Exception => resp.setStatus(HttpCode.INTERNAL_ERROR); ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("unexpected.result.from.update", e)) }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
            resp.setStatus(HttpCode.ACCESS_DENIED)
            ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.not.updated", service, t.getMessage))
          } else {
            resp.setStatus(HttpCode.BAD_INPUT)
            ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.not.updated", service, t.getMessage))
          }
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
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service", operation(deleteServices)) ({
    val orgid = params("orgid")
    val bareService = params("service")   // but do not have a hack/fix for the name
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.WRITE)
    // remove does *not* throw an exception if the key does not exist
    val resp = response
    db.run(ServicesTQ.getService(service).delete.transactionally.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/services/"+bareService+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
            AuthCache.services.removeOwner(service)
            AuthCache.services.removeIsPublic(service)
            resp.setStatus(HttpCode.DELETED)
            ApiResponse(ApiResponseType.OK, Messages("service.deleted"))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.not.found", service))
          }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.not.deleted", service, t.toString))
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/policy ================================ */
  val getServicePolicy =
    (apiOperation[ServicePolicy]("getServicePolicy")
      summary("Returns the service policy")
      description("""Returns the service policy. Can be run by a user or the service.""")
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("post.ok")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service/policy", operation(getServicePolicy)) ({
    val orgid = params("orgid")
    val bareService = params("service")
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.READ)
    val resp = response
    db.run(ServicePolicyTQ.getServicePolicy(service).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+bareService+"/policy result size: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.OK)
        list.head.toServicePolicy
      }
      else resp.setStatus(HttpCode.NOT_FOUND)
    })
  })

  // =========== PUT /orgs/{orgid}/services/{service}/policy ===============================
  val putServicePolicy =
    (apiOperation[ApiResponse]("putServicePolicy")
      summary "Adds/updates the service policy"
      description """Adds or updates the policy of a service. This is called by the owning user. The **request body** structure:

```
{
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing"
      "type": "string"   // optional, the type of the 'value': string, int, float, boolean, list of string, version
    }
  ],
  "constraints": [
    "a == b"
  ]
}
```"""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PutServicePolicyRequest],
        Option[String]("Policy object add or update. See details in the Implementation Notes above."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val putServicePolicy2 = (apiOperation[PutServicePolicyRequest]("putServicePolicy2") summary("a") description("a"))  // for some bizarre reason, the PutServicePolicyRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service/policy", operation(putServicePolicy)) ({
    val orgid = params("orgid")
    val bareService = params("service")
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.WRITE)
    val policy = try { parse(request.body).extract[PutServicePolicyRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }    // the specific exception is MappingException
    policy.validate()
    val resp = response
    db.run(policy.toServicePolicyRow(service).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/services/"+bareService+"/policy result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, Messages("policy.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("policy.not.inserted.or.updated", service, t.getMessage))
        } else {
          resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("policy.not.inserted.or.updated", service, t.toString))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service}/policy ===============================
  val deleteServicePolicy =
    (apiOperation[ApiResponse]("deleteServicePolicy")
      summary "Deletes the policy of a service"
      description "Deletes the policy of a service from the exchange DB. Can be run by the owning user."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service/policy", operation(deleteServicePolicy)) ({
    val orgid = params("orgid")
    val bareService = params("service")
    val service = OrgAndId(orgid,bareService).toString
    authenticate().authorizeTo(TService(service),Access.WRITE)
    val resp = response
    db.run(ServicePolicyTQ.getServicePolicy(service).delete.asTry).map({ xs =>
      logger.debug("DELETE /orgs/"+orgid+"/services/"+bareService+"/policy result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, Messages("service.policy.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.policy.not.found", service))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.policy.not.deleted", service, t.toString))
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
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service/keys", operation(getServiceKeys)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceKeysTQ.getKeys(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/services/"+id+"/keys result: "+list.toString)
      if (list.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
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
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service/keys/:keyid", operation(getOneServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+list.size)
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
        ApiResponse(ApiResponseType.NOT_FOUND, Messages("key.not.found", keyId))
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
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
        Parameter("body", DataType[String],
          Option[String]("Key object that needs to be added to, or updated in, the exchange. See details in the Implementation Notes above."),
          paramType = ParamType.Body)
      )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val putServiceKey2 = (apiOperation[String]("putKey2") summary("a") description("a"))  // for some bizarre reason, the PutKeysRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service/keys/:keyid", operation(putServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val keyReq = PutServiceKeyRequest(request.body)
    //val keyReq = try { parse(request.body).extract[PutServiceKeyRequest] }
    //catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "Error parsing the input body json: "+e)) }    // the specific exception is MappingException
    keyReq.validate(keyId)
    val resp = response
    db.run(keyReq.toServiceKeyRow(compositeId, keyId).upsert.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(_) => resp.setStatus(HttpCode.PUT_OK)
          ApiResponse(ApiResponseType.OK, Messages("key.added.or.updated"))
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
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
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service/keys", operation(deleteServiceAllKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceKeysTQ.getKeys(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/keys result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, Messages("service.keys.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, Messages("no.service.keys.found", compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.keys.not.deleted", compositeId, t.toString))
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
        Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
        Parameter("keyid", DataType.String, Option[String]("ID of the key."), paramType = ParamType.Path),
        Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
        Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
      )
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service/keys/:keyid", operation(deleteServiceKey)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val keyId = params("keyid")
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceKeysTQ.getKey(compositeId,keyId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/keys/"+keyId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, Messages("service.key.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.key.not.found", keyId, compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.key.not.deleted", keyId, compositeId, t.toString))
      }
    })
  })

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/dockauths ================================ */
  val getServiceDockAuths =
    (apiOperation[List[ServiceDockAuth]]("getServiceDockAuths")
      summary "Returns all docker image tokens for this service"
      description """Returns all the docker image authentication tokens for this service. Can be run by any credentials able to view the service."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("dockauthid", DataType.String, Option[String]("ID of the dockauth."), paramType = ParamType.Path),
      Parameter("id", DataType.String, Option[String]("Username of exchange user, or ID of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("token", DataType.String, Option[String]("Password of exchange user, or token of the node or agbot. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
    )
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service/dockauths", operation(getServiceDockAuths)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceDockAuthsTQ.getDockAuths(compositeId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/dockauths result size: "+list.size)
      //logger.trace("GET /orgs/"+orgid+"/services/"+id+"/dockauths result: "+list.toString)
      val listSorted = list.sortWith(_.dockAuthId < _.dockAuthId)
      if (listSorted.nonEmpty) resp.setStatus(HttpCode.OK)
      else resp.setStatus(HttpCode.NOT_FOUND)
      listSorted.map(_.toServiceDockAuth)
    })
  })

  /* ====== GET /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ================================ */
  val getOneServiceDockAuth =
    (apiOperation[ServiceDockAuth]("getOneServiceDockAuth")
      summary "Returns a docker image token for this service"
      description """Returns the docker image authentication token with the specified dockauthid for this service. Can be run by any credentials able to view the service."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("dockauthid", DataType.String, Option[String]("ID of the dockauth."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      produces "text/plain"
      responseMessages(ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  get("/orgs/:orgid/services/:service/dockauths/:dockauthid", operation(getOneServiceDockAuth)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val dockAuthId = try { params("dockauthid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "dockauthid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TService(compositeId),Access.READ)
    val resp = response
    db.run(ServiceDockAuthsTQ.getDockAuth(compositeId, dockAuthId).result).map({ list =>
      logger.debug("GET /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" result: "+list.size)
      if (list.nonEmpty) {
        resp.setStatus(HttpCode.OK)
        list.head.toServiceDockAuth
      }
      else {
        resp.setStatus(HttpCode.NOT_FOUND)
        list
      }
    })
  })

  // =========== POST /orgs/{orgid}/services/{service}/dockauths ===============================
  val postServiceDockAuth =
    (apiOperation[ApiResponse]("postServiceDockAuth")
      summary "Adds a docker image token for the service"
      description """Adds a new docker image authentication token for this service. As an optimization, if a dockauth resource already exists with the same service, registry, username, and token, this method will just update that lastupdated field. This can only be run by the service owning user."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutServiceDockAuthRequest],
        Option[String]("DockAuth object that needs to be added or updated in the exchange. Current supported values for 'username' are: 'token' (default) or 'iamapikey'."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val postServiceDockAuth2 = (apiOperation[PostPutServiceDockAuthRequest]("postDockAuth2") summary("a") description("a"))  // for some bizarre reason, the PostDockAuthsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  post("/orgs/:orgid/services/:service/dockauths", operation(postServiceDockAuth)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val dockAuthId = 0      // the db will choose a new id on insert
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val dockAuthIdReq = try { parse(request.body).extract[PostPutServiceDockAuthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }    // the specific exception is MappingException
    dockAuthIdReq.validate(dockAuthId)
    val resp = response
    db.run(dockAuthIdReq.getDupDockAuth(compositeId).result.asTry.flatMap({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services"+service+"/dockauths find duplicate: "+xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) ServiceDockAuthsTQ.getLastUpdatedAction(compositeId, v.head.dockAuthId).asTry    // there was a duplicate entry, so just update its lastUpdated field
          else dockAuthIdReq.toServiceDockAuthRow(compositeId, dockAuthId).insert.asTry     // no duplicate entry so add the one they gave us
        case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
      }
    })).map({ xs =>
      logger.debug("POST /orgs/"+orgid+"/services/"+service+"/dockauths result: "+xs.toString)
      xs match {
        case Success(n) => val num = n.toString.toInt     // num is either the id that was added, or (in the dup case) the number of rows that were updated (0 or 1)
          resp.setStatus(HttpCode.POST_OK)
          num match {
            case 0 => ApiResponse(ApiResponseType.OK, Messages("duplicate.dockauth.resource.already.exists"))    // we don't expect this, but it is possible, but only means that the lastUpdated field didn't get updated
            case 1 => ApiResponse(ApiResponseType.OK, Messages("dockauth.resource.updated"))    //todo: this can be 2 cases i dont know how to distinguish between: A) the 1st time anyone added a dockauth, or B) a dup was found and we updated it
            case _ => ApiResponse(ApiResponseType.OK, Messages("dockauth.num.added", num))    // we did not find a dup, so this is the dockauth id that was added
          }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage))
        }
      }
    })
  })

  // =========== PUT /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ===============================
  val putServiceDockAuth =
    (apiOperation[ApiResponse]("putServiceDockAuth")
      summary "Updates a docker image token for the service"
      description """Updates an existing docker image authentication token for this service. This can only be run by the service owning user."""
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("dockauthid", DataType.String, Option[String]("ID of the docker token."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false),
      Parameter("body", DataType[PostPutServiceDockAuthRequest],
        Option[String]("DockAuth object that needs to be added to, or updated in, the exchange. Current supported values for 'username' are: 'token' (default) or 'iamapikey'."),
        paramType = ParamType.Body)
    )
      responseMessages(ResponseMessage(HttpCode.POST_OK,Messages("created.updated")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.BAD_INPUT,Messages("bad.input")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )
  val putServiceDockAuth2 = (apiOperation[PostPutServiceDockAuthRequest]("putDockAuth2") summary("a") description("a"))  // for some bizarre reason, the PutDockAuthsRequest class has to be used in apiOperation() for it to be recognized in the body Parameter above

  put("/orgs/:orgid/services/:service/dockauths/:dockauthid", operation(putServiceDockAuth)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val dockAuthId = try { params("dockauthid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "dockauthid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val dockAuthIdReq = try { parse(request.body).extract[PostPutServiceDockAuthRequest] }
    catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, Messages("error.parsing.input.json", e))) }    // the specific exception is MappingException
    dockAuthIdReq.validate(dockAuthId)
    val resp = response
    db.run(dockAuthIdReq.toServiceDockAuthRow(compositeId, dockAuthId).update.asTry).map({ xs =>
      logger.debug("PUT /orgs/"+orgid+"/services/"+service+"/dockauths/"+dockAuthId+" result: "+xs.toString)
      xs match {
        case Success(n) => val numUpdated = n.toString.toInt     // n is an AnyRef so we have to do this to get it to an int
          if (numUpdated > 0) {
            resp.setStatus(HttpCode.PUT_OK)
            ApiResponse(ApiResponseType.OK, Messages("dockauth.updated", dockAuthId))
          } else {
            resp.setStatus(HttpCode.NOT_FOUND)
            ApiResponse(ApiResponseType.OK, Messages("dockauth.not.found", dockAuthId))
          }
        case Failure(t) => if (t.getMessage.startsWith("Access Denied:")) {
          resp.setStatus(HttpCode.ACCESS_DENIED)
          ApiResponse(ApiResponseType.ACCESS_DENIED, Messages("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage))
        } else {
          resp.setStatus(HttpCode.BAD_INPUT)
          ApiResponse(ApiResponseType.BAD_INPUT, Messages("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage))
        }
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service}/dockauths ===============================
  val deleteServiceAllDockAuth =
    (apiOperation[ApiResponse]("deleteServiceAllDockAuth")
      summary "Deletes all docker image auth tokens of a service"
      description "Deletes all of the current docker image auth tokens for this service. This can only be run by the service owning user."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service/dockauths", operation(deleteServiceAllDockAuth)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceDockAuthsTQ.getDockAuths(compositeId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/dockauths result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, Messages("service.dockauths.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, Messages("no.dockauths.found.for.service", compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.dockauths.not.deleted", compositeId, t.toString))
      }
    })
  })

  // =========== DELETE /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ===============================
  val deleteServiceDockAuth =
    (apiOperation[ApiResponse]("deleteServiceDockAuth")
      summary "Deletes a docker image auth token of a service"
      description "Deletes a docker image auth token for this service. This can only be run by the service owning user."
      parameters(
      Parameter("orgid", DataType.String, Option[String]("Organization id."), paramType=ParamType.Path),
      Parameter("service", DataType.String, Option[String]("Service id."), paramType=ParamType.Path),
      Parameter("dockauthid", DataType.String, Option[String]("ID of the dockauths."), paramType = ParamType.Path),
      Parameter("username", DataType.String, Option[String]("Username of owning user. This parameter can also be passed in the HTTP Header."), paramType = ParamType.Query, required=false),
      Parameter("password", DataType.String, Option[String]("Password of the user. This parameter can also be passed in the HTTP Header."), paramType=ParamType.Query, required=false)
    )
      responseMessages(ResponseMessage(HttpCode.DELETED,Messages("deleted")), ResponseMessage(HttpCode.BADCREDS,Messages("invalid.credentials")), ResponseMessage(HttpCode.ACCESS_DENIED,Messages("access.denied")), ResponseMessage(HttpCode.NOT_FOUND,Messages("not.found")))
      )

  delete("/orgs/:orgid/services/:service/dockauths/:dockauthid", operation(deleteServiceDockAuth)) ({
    val orgid = params("orgid")
    val service = params("service")   // but do not have a hack/fix for the name
    val compositeId = OrgAndId(orgid,service).toString
    val dockAuthId = try { params("dockauthid").toInt } catch { case e: Exception => halt(HttpCode.BAD_INPUT, ApiResponse(ApiResponseType.BAD_INPUT, "dockauthid must be an integer: "+e)) }    // the specific exception is NumberFormatException
    authenticate().authorizeTo(TService(compositeId),Access.WRITE)
    val resp = response
    db.run(ServiceDockAuthsTQ.getDockAuth(compositeId,dockAuthId).delete.asTry).map({ xs =>
      logger.debug("DELETE /services/"+service+"/dockauths/"+dockAuthId+" result: "+xs.toString)
      xs match {
        case Success(v) => if (v > 0) {        // there were no db errors, but determine if it actually found it or not
          resp.setStatus(HttpCode.DELETED)
          ApiResponse(ApiResponseType.OK, Messages("service.dockauths.deleted"))
        } else {
          resp.setStatus(HttpCode.NOT_FOUND)
          ApiResponse(ApiResponseType.NOT_FOUND, Messages("service.dockauths.not.found", dockAuthId, compositeId))
        }
        case Failure(t) => resp.setStatus(HttpCode.INTERNAL_ERROR)
          ApiResponse(ApiResponseType.INTERNAL_ERROR, Messages("service.dockauths.not.deleted", dockAuthId, compositeId, t.toString))
      }
    })
  })

}