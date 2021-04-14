/** Services routes for all of the /orgs/{orgid}/services api methods. */
package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.horizon.exchangeapi.auth._
import de.heikoseeberger.akkahttpjackson._
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._

import scala.concurrent.ExecutionContext
import com.horizon.exchangeapi.tables._
import org.json4s._
import java.net.{MalformedURLException, URL}

import com.horizon.exchangeapi
import org.json4s.jackson.Serialization.write
import slick.jdbc
import slick.jdbc.PostgresProfile
import slick.jdbc.PostgresProfile.api._

import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.util._
import scala.util.control.Breaks._

//====== These are the input and output structures for /orgs/{orgid}/services routes. Swagger and/or json seem to require they be outside the trait.

/** Output format for GET /orgs/{orgid}/services */
final case class GetServicesResponse(services: Map[String,Service], lastIndex: Int)
final case class GetServiceAttributeResponse(attribute: String, value: String)

object GetServicesUtils {
  def getServicesProblem(public: Option[String], version: Option[String], nodetype: Option[String]): Option[String] = {
    if (public.isDefined && !(public.get.toLowerCase == "true" || public.get.toLowerCase == "false")) return Some(ExchMsg.translate("bad.public.param"))
    if (version.isDefined && !Version(version.get).isValid) return Some(ExchMsg.translate("version.not.valid.format", version.get))
    if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase)) return Some(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
    None
  }
}

object SharableVals extends Enumeration {
  type SharableVals = Value
  val EXCLUSIVE: exchangeapi.SharableVals.Value = Value("exclusive")
  val SINGLE: exchangeapi.SharableVals.Value = Value("single")    // this is being replaced by singleton
  val SINGLETON: exchangeapi.SharableVals.Value = Value("singleton")
  val MULTIPLE: exchangeapi.SharableVals.Value = Value("multiple")
}

/** Input format for POST /orgs/{orgid}/services or PUT /orgs/{orgid}/services/<service-id> */
final case class PostPutServiceRequest(label: String, description: Option[String], public: Boolean, documentation: Option[String], url: String, version: String, arch: String, sharable: String, matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]],secrets: Option[List[Map[String,String]]],deployment: Option[String], deploymentSignature: Option[String], clusterDeployment: Option[String], clusterDeploymentSignature: Option[String], imageStore: Option[Map[String,Any]]) {
  require(label!=null && url!=null && version!=null && arch!=null && sharable!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(orgid: String, serviceId: String): Option[String] = {

    // Ensure that the documentation field is a valid URL
    if (documentation.getOrElse("") != "") {
      try { new URL(documentation.getOrElse("")) }
      catch { case _: MalformedURLException => return Some(ExchMsg.translate("documentation.field.not.valid.url")) }
    }

    if (!Version(version).isValid) return Some(ExchMsg.translate("version.not.valid.format", version))
    if (arch == "") return Some(ExchMsg.translate("arch.cannot.be.empty"))

    // We enforce that the attributes equal the existing id for PUT, because even if they change the attribute, the id would not get updated correctly
    if (serviceId != null && serviceId != "" && formId(orgid) != serviceId) return Some(ExchMsg.translate("service.id.does.not.match"))

    val allSharableVals: Set[String] = SharableVals.values.map(_.toString)
    if (sharable == "" || !allSharableVals.contains(sharable)) return Some(ExchMsg.translate("invalid.sharable.value", sharable))

    // Check for requiring a service that is a different arch than this service
    for (rs <- requiredServices.getOrElse(List())) {
      if(rs.versionRange.isEmpty && rs.version.isEmpty) return Some(ExchMsg.translate("no.version.range.in.req.service", rs.url))
      if (rs.arch != arch) return Some(ExchMsg.translate("req.service.has.wrong.arch", rs.url, rs.arch, arch))
    }

    // Check that it is signed
    //if (deployment.getOrElse("") == "" && clusterDeployment.getOrElse("") == "") return Some(ExchMsg.translate("service.no.deployment"))  // <- not forcing this
    if (deployment.getOrElse("") != "" && deploymentSignature.getOrElse("") == "") return Some(ExchMsg.translate("service.def.not.signed"))
    if (clusterDeployment.getOrElse("") != "" && clusterDeploymentSignature.getOrElse("") == "") return Some(ExchMsg.translate("service.def.not.signed"))
    None
  }

  // Build a list of db actions to verify that the referenced services exist
  def validateServiceIds: DBIO[Vector[Int]] = {
    if (requiredServices.isEmpty || requiredServices.get.isEmpty) return DBIO.successful(Vector())
    val actions: ListBuffer[jdbc.PostgresProfile.api.DBIO[Int]] = ListBuffer[DBIO[Int]]()
    for (m <- requiredServices.get) {
      val finalVersionRange: String = if(m.versionRange.isEmpty) m.version.getOrElse("") else m.versionRange.getOrElse("")
      val svcId: String = ServicesTQ.formId(m.org, m.url, finalVersionRange, m.arch)     // need to wildcard version, because it is an osgi version range
      actions += ServicesTQ.getService(svcId).length.result
    }
    DBIO.sequence(actions.toVector)      // convert the list of actions to a DBIO sequence because that returns query values
  }

  def formId(orgid: String): String = ServicesTQ.formId(orgid, url, version, arch)

  def toServiceRow(service: String, orgid: String, owner: String): ServiceRow = ServiceRow(service, orgid, owner, label, description.getOrElse(label), public, documentation.getOrElse(""), url, version, arch, sharable, write(matchHardware), write(requiredServices), write(userInput), write(secrets),deployment.getOrElse(""), deploymentSignature.getOrElse(""), clusterDeployment.getOrElse(""), clusterDeploymentSignature.getOrElse(""), write(imageStore), ApiTime.nowUTC)
}

final case class PatchServiceRequest(label: Option[String], description: Option[String], public: Option[Boolean], documentation: Option[String], url: Option[String], version: Option[String], arch: Option[String], sharable: Option[String], matchHardware: Option[Map[String,Any]], requiredServices: Option[List[ServiceRef]], userInput: Option[List[Map[String,String]]], secrets: Option[List[Map[String,String]]], deployment: Option[String], deploymentSignature: Option[String], clusterDeployment: Option[String], clusterDeploymentSignature: Option[String], imageStore: Option[Map[String,Any]]) {
   protected implicit val jsonFormats: Formats = DefaultFormats

  def getAnyProblem: Option[String] = {
    /* if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else */ None
  }

  /** Returns a tuple of the db action to update parts of the service, and the attribute name being updated. */
  def getDbUpdate(service: String, orgid: String): (DBIO[_],String) = {
    val lastUpdated: String = ApiTime.nowUTC
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
    secrets match { case Some(ser) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.secrets,d.lastUpdated)).update((service, write(ser), lastUpdated)), "secrets"); case _ => ; }
    deployment match { case Some(dep) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.deployment,d.lastUpdated)).update((service, dep, lastUpdated)), "deployment"); case _ => ; }
    deploymentSignature match { case Some(depsig) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.deploymentSignature,d.lastUpdated)).update((service, depsig, lastUpdated)), "deploymentSignature"); case _ => ; }
    imageStore match { case Some(p) => return ((for {d <- ServicesTQ.rows if d.service === service } yield (d.service,d.imageStore,d.lastUpdated)).update((service, write(p), lastUpdated)), "imageStore"); case _ => ; }
    (null, null)
  }
}


final case class PutServicePolicyRequest(label: Option[String], description: Option[String], properties: Option[List[OneProperty]], constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of strings", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        return Some(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
      }
    }
    None
  }

  def toServicePolicyRow(serviceId: String): ServicePolicyRow = ServicePolicyRow(serviceId, label.getOrElse(""), description.getOrElse(label.getOrElse("")), write(properties), write(constraints), ApiTime.nowUTC)
}


/** Input format for PUT /orgs/{orgid}/services/{service}/keys/<key-id> */
final case class PutServiceKeyRequest(key: String) {
  require(key!=null)
  def toServiceKey: ServiceKey = ServiceKey(key, ApiTime.nowUTC)
  def toServiceKeyRow(serviceId: String, keyId: String): ServiceKeyRow = ServiceKeyRow(keyId, serviceId, key, ApiTime.nowUTC)
  def getAnyProblem: Option[String] = None
}


/** Response for GET /orgs/{orgid}/agbots/{id}/msgs */
//final case class GetServiceDockAuthResponse(dockauths: List[ServiceDockAuth], lastIndex: Int)

/** Input format for POST /orgs/{orgid}/services/{service}/dockauths or PUT /orgs/{orgid}/services/{service}/dockauths/{dockauthid} */
final case class PostPutServiceDockAuthRequest(registry: String, username: Option[String], token: String) {
  def toServiceDockAuthRow(serviceId: String, dockAuthId: Int): ServiceDockAuthRow = ServiceDockAuthRow(dockAuthId, serviceId, registry, username.getOrElse("token"), token, ApiTime.nowUTC)
  def getAnyProblem(dockauthIdAsStr: Option[String]): Option[String] = {
    if (dockauthIdAsStr.isEmpty) None
    else Try(dockauthIdAsStr.get.toInt) match {
      case Success(_) => None
      case Failure(t) => Some("dockauthid must be an integer: " + t.getMessage)
    }
  }
  def getDupDockAuth(serviceId: String): Query[ServiceDockAuths, ServiceDockAuthRow, Seq] = ServiceDockAuthsTQ.getDupDockAuth(serviceId, registry, username.getOrElse("token"), token)
}



/** Implementation for all of the /orgs/{orgid}/services routes */
@Path("/v1/orgs/{orgid}/services")
trait ServicesRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def servicesRoutes: Route = servicesGetRoute ~ serviceGetRoute ~ servicePostRoute ~ servicePutRoute ~ servicePatchRoute ~ serviceDeleteRoute ~ serviceGetPolicyRoute ~ servicePutPolicyRoute ~ serviceDeletePolicyRoute ~ serviceGetKeysRoute ~ serviceGetKeyRoute ~ servicePutKeyRoute ~ serviceDeleteKeysRoute ~ serviceDeleteKeyRoute ~ serviceGetDockauthsRoute ~ serviceGetDockauthRoute ~ servicePostDockauthRoute ~ servicePutDockauthRoute ~ serviceDeleteDockauthsRoute ~ serviceDeleteDockauthRoute

  // ====== GET /orgs/{orgid}/services ================================
  @GET
  @Path("")
  @Operation(summary = "Returns all services", description = "Returns all service definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this public setting"),
      new Parameter(name = "url", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this url (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "version", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this version (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "arch", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services with this arch (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "nodetype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services that are deployable on this nodeType. Valid values: devices or clusters"),
      new Parameter(name = "requiredurl", in = ParameterIn.QUERY, required = false, description = "Filter results to only include services that use this service with this url (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "services": {
    "orgid/servicename": {
      "owner": "string",
      "label": "string",
      "description": "blah blah",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "1.2.3",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "secrets":[],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.221Z[UTC]"
    },
    "orgid/servicename2": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "4.5.6",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [
        {
          "url": "string",
          "org": "string",
          "version": "[1.0.0,INFINITY)",
          "versionRange": "[1.0.0,INFINITY)",
          "arch": "string"
        }
      ],
      "userInput": [
        {
          "name": "foo",
          "label": "The Foo Value",
          "type": "string",
          "defaultValue": "bar"
        }
      ],
     "secrets": [
  "secret1": {
    "type": "string",
    "description": "string"
 }
],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.680Z[UTC]"
    },
    "orgid/servicename3": {
      "owner": "string",
      "label": "string",
      "description": "fake",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "string",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "secrets": [],
      "deployment": "",
      "deploymentSignature": "",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-12-13T15:38:57.679Z[UTC]"
    },
      ...
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetServicesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def servicesGetRoute: Route = (path("orgs" / Segment / "services") & get & parameter((Symbol("owner").?, Symbol("public").?, Symbol("url").?, Symbol("version").?, Symbol("arch").?, Symbol("nodetype").?, Symbol("requiredurl").?))) { (orgid, owner, public, url, version, arch, nodetype, requiredurl) =>
    exchAuth(TService(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validateWithMsg(GetServicesUtils.getServicesProblem(public, version, nodetype)) {
        complete({
          //var q = ServicesTQ.rows.subquery
          var q = ServicesTQ.getAllServices(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          url.foreach(url => { if (url.contains("%")) q = q.filter(_.url like url) else q = q.filter(_.url === url) })
          version.foreach(version => { if (version.contains("%")) q = q.filter(_.version like version) else q = q.filter(_.version === version) })
          arch.foreach(arch => { if (arch.contains("%")) q = q.filter(_.arch like arch) else q = q.filter(_.arch === arch) })
          nodetype.foreach(nt => { if (nt == "device") q = q.filter(_.deployment =!= "") else if (nt == "cluster") q = q.filter(_.clusterDeployment =!= "") })

          // We are cheating a little on this one because the whole requiredServices structure is serialized into a json string when put in the db, so it has a string value like
          // [{"url":"mydomain.com.rtlsdr","version":"1.0.0","arch":"amd64"}]. But we can still match on the url.
          requiredurl.foreach(requrl => {
            val requrl2: String = "%\"url\":\"" + requrl + "\"%"
            q = q.filter(_.requiredServices like requrl2)
          })

          db.run(q.result).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/services result size: "+list.size)
            val services: Map[String, Service] = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.service -> e.toService).toMap
            val code: StatusCode with Serializable = if (services.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetServicesResponse(services, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  // ====== GET /orgs/{orgid}/services/{service} ================================
  @GET
  @Path("{service}")
  @Operation(summary = "Returns a service", description = "Returns the service with the specified id. Can be run by a user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service id."),
      new Parameter(name = "attribute", in = ParameterIn.QUERY, required = false, description = "Which attribute value should be returned. Only 1 attribute can be specified. If not specified, the entire service resource will be returned")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
          new Content(
            examples = Array(
              new ExampleObject(
                value ="""{
  "services": {
    "orgid/servicename": {
      "owner": "string",
      "label": "string",
      "description": "blah blah",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "1.2.3",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "secrets": [],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.221Z[UTC]"
    },
    "orgid/servicename2": {
      "owner": "string",
      "label": "string",
      "description": "string",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "4.5.6",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [
        {
          "url": "string",
          "org": "string",
          "version": "[1.0.0,INFINITY)",
          "versionRange": "[1.0.0,INFINITY)",
          "arch": "string"
        }
      ],
      "userInput": [
        {
          "name": "foo",
          "label": "The Foo Value",
          "type": "string",
          "defaultValue": "bar"
        }
      ],
      "secrets": [
  "secret1": {
    "type": "string",
    "description": "string"
 }
],
      "deployment": "string",
      "deploymentSignature": "string",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-05-14T16:20:40.680Z[UTC]"
    },
    "orgid/servicename3": {
      "owner": "string",
      "label": "string",
      "description": "fake",
      "public": true,
      "documentation": "",
      "url": "string",
      "version": "string",
      "arch": "string",
      "sharable": "singleton",
      "matchHardware": {},
      "requiredServices": [],
      "userInput": [],
      "secrets": [],
      "deployment": "",
      "deploymentSignature": "",
      "clusterDeployment": "",
      "clusterDeploymentSignature": "",
      "imageStore": {},
      "lastUpdated": "2019-12-13T15:38:57.679Z[UTC]"
    }
  },
  "lastIndex": 0
}"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[GetServicesResponse])
          )
        )),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def serviceGetRoute: Route = (path("orgs" / Segment / "services" / Segment) & get & parameter((Symbol("attribute").?))) { (orgid, service, attribute) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.READ) { _ =>
      complete({
        attribute match {
          case Some(attribute) =>  // Only returning 1 attr of the service
            val q = ServicesTQ.getAttribute(compositeId, attribute) // get the proper db query for this attribute
            if (q == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("attribute.not.part.of.service", attribute)))
            else db.run(q.result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/services/" + service + " attribute result: " + list.toString)
              if (list.nonEmpty) {
                (HttpCode.OK, GetServiceAttributeResponse(attribute, list.head.toString))
              } else {
                (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
              }
            })

          case None =>  // Return the whole service resource
            db.run(ServicesTQ.getService(compositeId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/services result size: " + list.size)
              val services: Map[String, Service] = list.map(e => e.service -> e.toService).toMap
              val code: StatusCode with Serializable = if (services.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetServicesResponse(services, 0))
            })
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/services ===============================
  @POST
  @Path("")
  @Operation(
    summary = "Adds a service",
    description = "A service resource contains the metadata that Horizon needs to deploy the docker images that implement this service. A service can either be an edge application, or a lower level edge service that provides access to sensors or reusable features. The service can require 1 or more other services that Horizon should also deploy when deploying this service. If public is set to true, the service can be shared across organizations. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "Location for amd64",                                                            // this will be displayed in the node registration UI
  "description": "blah blah",
  "public": true,                                                                           // whether or not it can be viewed by other organizations
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",  // description of what this service does and how to use it
  "url": "github.com.open-horizon.examples.sdr2msghub",                                     // the unique identifier of this service
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",        // if multiple services require this service, how many instances are deployed: ["exclusive", "singleton", "multiple"]
  // The other services this service requires. (The other services must exist in the exchange before creating this service.)
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",  // an OSGI-formatted version range
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",          // ["boolean", "float", "int", "list of strings", "string"]
      "defaultValue": "bar"      // if empty then the node owner must provide a value at registration time
    }
  ],
  "secrets": {
  "secret1": {
    "type": "string",
    "description": "string"
 }
},
  // Information about how to deploy the docker images for this service
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",         // container deployment info on edge devices. Can be omitted if does not apply
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                                 // filled in by the Horizon signing process
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",  // container deployment info on edge clusters. Can be omitted if does not apply
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                          // filled in by the Horizon signing process
  "imageStore": {                                                                                                      // can be omitted
    "storeType": "dockerRegistry"                                                                                      // dockerRegistry is the only supported value right now, and is the default
  }
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutServiceRequest])
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def servicePostRoute: Route = (path("orgs" / Segment / "services") & post & entity(as[PostPutServiceRequest])) { (orgid, reqBody) =>
    exchAuth(TService(OrgAndId(orgid,"").toString), Access.CREATE) { ident =>
      validateWithMsg(reqBody.getAnyProblem(orgid, null)) {
        complete({
          val service: String = reqBody.formId(orgid)
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update services, so owner will never be blank

          // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
          // We'll look for versions within the required ranges in the db access routine below.
          val svcIds: Seq[String] = reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
          val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
          else {
            // The inner map() and reduceLeft() OR together all of the likes to give to filter()
            ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
          }

          db.run(svcAction.asTry.flatMap({
            case Success(rows) =>
              logger.debug("POST /orgs/" + orgid + "/services requiredServices validation: " + rows)
              var invalidIndex: Int = -1
              var invalidSvcRef: ServiceRef = ServiceRef("", "", Some(""), Some(""), "")
              // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
              // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
              breakable {
                for ((svcRef, index) <- reqBody.requiredServices.getOrElse(List()).zipWithIndex) {
                  breakable {
                    for ((orgid, url, version, arch) <- rows) {
                      //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                      val finalVersionRange: String = if (svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                      if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange))) break() // we satisfied this requiredService so move on to the next
                    }
                    invalidIndex = index // we finished the inner loop but did not find a service that satisfied the requirement
                    invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
                  } //  if we found a service that satisfies the requirement, it breaks to this line
                  if (invalidIndex >= 0) break() // a requiredService was not satisfied, so break out of the outer loop and return an error
                }
              }
              if (invalidIndex < 0) ServicesTQ.getNumOwned(owner).result.asTry // we are good, move on to the next step
              else {
                //else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service in requiredServices does not exist in the exchange")).asTry
                val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(num) =>
              logger.debug("POST /orgs/" + orgid + "/services num owned by " + owner + ": " + num)
              val numOwned: Int = num
              val maxServices: Int = ExchConfig.getInt("api.limits.maxServices")
              if (maxServices == 0 || maxServices >= numOwned) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                reqBody.toServiceRow(service, orgid, owner).insert.asTry
              }
              else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.the.limit.of.services", maxServices))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(v) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/services result: " + v)
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, reqBody.public, ResChangeResource.SERVICE, ResChangeOperation.CREATED).insert.asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/services added to changes table: " + v)
              if (owner != "") AuthCache.putServiceOwner(service, owner) // currently only users are allowed to update service resources, so owner should never be blank
              AuthCache.putServiceIsPublic(service, reqBody.public)
              (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.created", service)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("service.already.exists", service, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.created", service, t.getMessage))
            case Failure(t) =>
              (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.created", service, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/services/{service} ===============================
  @PUT
  @Path("{service}")
  @Operation(summary = "Updates a service", description = "Does a full replace of an existing service. See the description of the body fields in the POST method. This can only be called by the user that originally created it.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service id.")),
    requestBody = new RequestBody(content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "Location for amd64",                                                            // this will be displayed in the node registration UI
  "description": "blah blah",
  "public": true,                                                                           // whether or not it can be viewed by other organizations
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",  // description of what this service does and how to use it
  "url": "github.com.open-horizon.examples.sdr2msghub",                                     // the unique identifier of this service
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",        // if multiple services require this service, how many instances are deployed: ["exclusive", "singleton", "multiple"]
  // The other services this service requires. (The other services must exist in the exchange before creating this service.)
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",  // an OSGI-formatted version range
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",          // ["boolean", "float", "int", "list of strings", "string"]
      "defaultValue": "bar"      // if empty then the node owner must provide a value at registration time
    }
  ],
  // Information about how to deploy the docker images for this service
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",         // container deployment info on edge devices. Can be omitted if does not apply
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                                 // filled in by the Horizon signing process
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",  // container deployment info on edge clusters. Can be omitted if does not apply
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                          // filled in by the Horizon signing process
  "imageStore": {                                                                                                      // can be omitted
    "storeType": "dockerRegistry"                                                                                      // dockerRegistry is the only supported value right now, and is the default
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PostPutServiceRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def servicePutRoute: Route = (path("orgs" / Segment / "services" / Segment) & put & entity(as[PostPutServiceRequest])) { (orgid, service, reqBody) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { ident =>
      validateWithMsg(reqBody.getAnyProblem(orgid, compositeId)) {
        complete({
          val owner: String = ident match { case IUser(creds) => creds.id; case _ => "" }   // currently only users are allowed to create/update services, so owner will never be blank

          // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
          // We'll look for versions within the required ranges in the db access routine below.
          val svcIds: Seq[String] = reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
          val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
          else {
            // The inner map() and reduceLeft() OR together all of the likes to give to filter()
            ServicesTQ.rows.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
          }

          db.run(svcAction.asTry.flatMap({
            case Success(rows) =>
              logger.debug("POST /orgs/" + orgid + "/services requiredServices validation: " + rows)
              var invalidIndex: Int = -1
              var invalidSvcRef: ServiceRef = ServiceRef("", "", Some(""), Some(""), "")
              // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
              // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
              breakable {
                for ((svcRef, index) <- reqBody.requiredServices.getOrElse(List()).zipWithIndex) {
                  breakable {
                    for ((orgid, specRef, version, arch) <- rows) {
                      //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                      val finalVersionRange: String = if (svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                      if (specRef == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange))) break() // we satisfied this apiSpec requirement so move on to the next
                    }
                    invalidIndex = index // we finished the inner loop but did not find a service that satisfied the requirement
                    invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
                  } //  if we found a service that satisfies the requirment, it breaks to this line
                  if (invalidIndex >= 0) break() // an requiredService was not satisfied, so break out and return an error
                }
              }
              if (invalidIndex < 0) reqBody.toServiceRow(compositeId, orgid, owner).update.asTry // we are good, move on to the next step
              else {
                val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                DBIO.failed(new Throwable(errStr)).asTry
              }
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(n) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + " result: " + n)
              val numUpdated: Int = n.asInstanceOf[Int] // i think n is an AnyRef so we have to do this to get it to an int
              if (numUpdated > 0) {
                if (owner != "") AuthCache.putServiceOwner(compositeId, owner) // currently only users are allowed to update service resources, so owner should never be blank
                AuthCache.putServiceIsPublic(compositeId, reqBody.public)
                val serviceId: String = compositeId.substring(compositeId.indexOf("/") + 1, compositeId.length)
                ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, reqBody.public, ResChangeResource.SERVICE, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else {
                DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
              }
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + " updated in changes table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.updated")))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.updated", compositeId, t.getMessage))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
              else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PATCH /orgs/{orgid}/services/{service} ===============================
  @PATCH
  @Path("{service}")
  @Operation(summary = "Updates 1 attribute of a service", description = "Updates one attribute of a service. This can only be called by the user that originally created this service resource.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    requestBody = new RequestBody(description = "Specify only **one** of the attributes", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "label": "Location for amd64",                                                            // this will be displayed in the node registration UI
  "description": "blah blah",
  "public": true,                                                                           // whether or not it can be viewed by other organizations
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",  // description of what this service does and how to use it
  "url": "github.com.open-horizon.examples.sdr2msghub",                                     // the unique identifier of this service
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",        // if multiple services require this service, how many instances are deployed: ["exclusive", "singleton", "multiple"]
  // The other services this service requires. (The other services must exist in the exchange before creating this service.)
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",  // an OSGI-formatted version range
      "arch": "amd64"
    }
  ],
  // Values the node owner will be prompted for and will be set as env vars to the container.
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",          // ["boolean", "float", "int", "list of strings", "string"]
      "defaultValue": "bar"      // if empty then the node owner must provide a value at registration time
    }
  ],
  "secrets": [
  "secret1": {
    "type": "string",
    "description": "string"
 }
],
  // Information about how to deploy the docker images for this service
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",         // container deployment info on edge devices. Can be omitted if does not apply
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                                 // filled in by the Horizon signing process
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",  // container deployment info on edge clusters. Can be omitted if does not apply
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",                                                          // filled in by the Horizon signing process
  "imageStore": {                                                                                                      // can be omitted
    "storeType": "dockerRegistry"                                                                                      // dockerRegistry is the only supported value right now, and is the default
  }
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PatchServiceRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body:",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def servicePatchRoute: Route = (path("orgs" / Segment / "services" / Segment) & patch & entity(as[PatchServiceRequest])) { (orgid, service, reqBody) =>
    logger.debug(s"Doing PATCH /orgs/$orgid/services/$service")
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          val (action, attrName) = reqBody.getDbUpdate(compositeId, orgid)
          if (action == null) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("no.valid.service.attr.specified")))
          else if (attrName == "url" || attrName == "version" || attrName == "arch") (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("cannot.patch.these.attributes")))
          else if (attrName == "sharable" && !SharableVals.values.map(_.toString).contains(reqBody.sharable.getOrElse(""))) (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.value.for.sharable.attribute", reqBody.sharable.getOrElse(""))))
          else {
            // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
            // We'll look for versions within the required ranges in the db access routine below.
            val svcIds: Seq[String] = if (attrName == "requiredServices") reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch)) else List()
            val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector()) // no services to look for
            else {
              // The inner map() and reduceLeft() OR together all of the likes to give to filter()
              ServicesTQ.rows.filter(s => {
                svcIds.map(s.service like _).reduceLeft(_ || _)
              }).map(s => (s.orgid, s.url, s.version, s.arch)).result
            }

            // First check that the requiredServices exist (if that is not what they are patching, this is a noop)
            //todo: add a step to update the owner, if different
            db.run(svcAction.transactionally.asTry.flatMap({
              case Success(rows) =>
                logger.debug("PATCH /orgs/" + orgid + "/services requiredServices validation: " + rows)
                var invalidIndex: Int = -1
                var invalidSvcRef: ServiceRef = ServiceRef("", "", Some(""), Some(""), "")
                // rows is a sequence of some ServiceRow cols which is a superset of what we need. Go thru each requiredService in the request and make
                // sure there is an service that matches the version range specified. If the requiredServices list is empty, this will fall thru and succeed.
                breakable {
                  for ((svcRef, index) <- reqBody.requiredServices.getOrElse(List()).zipWithIndex) {
                    breakable {
                      for ((orgid, url, version, arch) <- rows) {
                        //logger.debug("orgid: "+orgid+", url: "+url+", version: "+version+", arch: "+arch)
                        val finalVersionRange: String = if (svcRef.versionRange.isEmpty) svcRef.version.getOrElse("") else svcRef.versionRange.getOrElse("")
                        if (url == svcRef.url && orgid == svcRef.org && arch == svcRef.arch && (Version(version) in VersionRange(finalVersionRange))) break() // we satisfied this requiredService so move on to the next
                      }
                      invalidIndex = index // we finished the inner loop but did not find a service that satisfied the requirement
                      invalidSvcRef = ServiceRef(svcRef.url, svcRef.org, svcRef.version, svcRef.versionRange, svcRef.arch)
                    } //  if we found a service that satisfies the requirement, it breaks to this line
                    if (invalidIndex >= 0) break() // a requiredService was not satisfied, so break out of the outer loop and return an error
                  }
                }
                if (invalidIndex < 0) action.transactionally.asTry // we are good, move on to the real patch action
                else {
                  val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
            }).flatMap({
              case Success(v) =>
                // Get the value of the public field
                logger.debug("PUT /orgs/" + orgid + "/services/" + service + " result: " + v)
                val numUpdated: Int = v.asInstanceOf[Int] // v comes to us as type Any
                if (numUpdated > 0) { // there were no db errors, but determine if it actually found it or not
                  if (attrName == "public") AuthCache.putServiceIsPublic(compositeId, reqBody.public.getOrElse(false))
                  ServicesTQ.getPublic(compositeId).result.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(public) =>
                // Add the resource to the resourcechanges table
                logger.debug("PUT /orgs/" + orgid + "/services/" + service + " public field: " + public)
                if (public.nonEmpty) {
                  val serviceId: String = compositeId.substring(compositeId.indexOf("/") + 1, compositeId.length)
                  var publicField = false
                  if (reqBody.public.isDefined) {
                    publicField = reqBody.public.getOrElse(false)
                  }
                  else {
                    publicField = public.head
                  }
                  ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, publicField, ResChangeResource.SERVICE, ResChangeOperation.MODIFIED).insert.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PATCH /orgs/" + orgid + "/services/" + service + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.attr.updated", attrName, compositeId)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
                else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.updated", compositeId, t.getMessage))
              case Failure(t) =>
                if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
                else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.updated", compositeId, t.getMessage)))
            })
          }
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service} ===============================
  @DELETE
  @Path("{service}")
  @Operation(summary = "Deletes a service", description = "Deletes a service. Can only be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service")
  def serviceDeleteRoute: Route = (path("orgs" / Segment / "services" / Segment) & delete) { (orgid, service) =>
    logger.debug(s"Doing DELETE /orgs/$orgid/services/$service")
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        // remove does *not* throw an exception if the key does not exist
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before doing the deletion
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + " public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServicesTQ.getService(compositeId).delete.transactionally.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              AuthCache.removeServiceOwner(compositeId)
              AuthCache.removeServiceIsPublic(compositeId)
              val serviceId: String = compositeId.substring(compositeId.indexOf("/") + 1, compositeId.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICE, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/policy ================================ */
  @GET
  @Path("{service}/policy")
  @Operation(summary = "Returns the service policy", description = "Returns the service policy. Can be run by a user, node or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ServicePolicy])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/policy")
  def serviceGetPolicyRoute: Route = (path("orgs" / Segment / "services" / Segment / "policy") & get) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.READ) { _ =>
      complete({
        db.run(ServicePolicyTQ.getServicePolicy(compositeId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/services/"+service+"/policy result size: "+list.size)
          if (list.nonEmpty) (HttpCode.OK, list.head.toServicePolicy)
          else (HttpCode.NOT_FOUND, ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found")))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/services/{service}/policy ===============================
  @PUT
  @Path("{service}/policy")
  @Operation(
    summary = "Adds/updates the service policy",
    description = "Adds or updates the policy of a service. This can be called by the owning user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "service",
        in = ParameterIn.PATH,
        description = "ID of the service to be updated."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "label": "human readable name of the service policy",  // this will be displayed in the UI
  "description": "descriptive text",
  "properties": [
    {
      "name": "mypurpose",
      "value": "myservice-testing"
      "type": "string"              // (optional) [boolean, float, int, list of strings, string, version]
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
          schema = new Schema(implementation = classOf[PutServicePolicyRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/policy")
  def servicePutPolicyRoute: Route = (path("orgs" / Segment / "services" / Segment / "policy") & put & entity(as[PutServicePolicyRequest])) { (orgid, service, reqBody) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem) {
        complete({
          db.run(reqBody.toServicePolicyRow(compositeId).upsert.asTry.flatMap({
            case Success(v) =>
              // Get the value of the public field
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/policy result: " + v)
              ServicesTQ.getPublic(compositeId).result.asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(public) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/policy public field: " + public)
              if (public.nonEmpty) {
                val serviceId: String = compositeId.substring(compositeId.indexOf("/") + 1, compositeId.length)
                ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEPOLICIES, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/policy updated in changes table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("policy.added.or.updated")))
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("policy.not.inserted.or.updated", compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("policy.not.inserted.or.updated", compositeId, t.toString))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("policy.not.inserted.or.updated", compositeId, t.getMessage)))
              else (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("policy.not.inserted.or.updated", compositeId, t.toString)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service}/policy ===============================
  @DELETE
  @Path("{service}/policy")
  @Operation(summary = "Deletes the policy of a service", description = "Deletes the policy of a service. Can be run by the owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/policy")
  def serviceDeletePolicyRoute: Route = (path("orgs" / Segment / "services" / Segment / "policy") & delete) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before doing the delete
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + "/policy public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServicePolicyTQ.getServicePolicy(compositeId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + "/policy result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = compositeId.substring(compositeId.indexOf("/") + 1, compositeId.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEPOLICIES, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.policy.not.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /orgs/" + orgid + "/services/" + service + "/policy updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.policy.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.policy.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.policy.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/keys ================================ */
  @GET
  @Path("{service}/keys")
  @Operation(summary = "Returns all keys/certs for this service", description = "Returns all the signing public keys/certs for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
            new Content(
            examples = Array(
              new ExampleObject(
                value = """
[
  "mykey.pem"
]
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[List[String]])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def serviceGetKeysRoute: Route = (path("orgs" / Segment / "services" / Segment / "keys") & get) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.READ) { _ =>
      complete({
        db.run(ServiceKeysTQ.getKeys(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/services/$service/keys result size: ${list.size}")
          val code: StatusCode with Serializable = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.map(_.keyId))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/services/{service}/keys/{keyid} ================================ */
  @GET
  @Path("{service}/keys/{keyid}")
  @Operation(summary = "Returns a key/cert for this service", description = "Returns the signing public key/cert with the specified keyid for this service. The raw content of the key/cert is returned, not json. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "Key Id.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def serviceGetKeyRoute: Route = (path("orgs" / Segment / "services" / Segment / "keys" / Segment) & get) { (orgid, service, keyId) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.READ) { _ =>
      complete({
        db.run(ServiceKeysTQ.getKey(compositeId, keyId).result).map({ list =>
          logger.debug("GET /orgs/"+orgid+"/services/"+service+"/keys/"+keyId+" result: "+list.size)
          // Note: both responses must be the same content type or that doesn't get set correctly
          if (list.nonEmpty) HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, list.head.key))
          else HttpResponse(status = HttpCode.NOT_FOUND, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, ""))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/services/{service}/keys/{keyid} ===============================
  @PUT
  @Path("{service}/keys/{keyid}")
  @Operation(summary = "Adds/updates a key/cert for the service", description = "Adds a new signing public key/cert, or updates an existing key/cert, for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service to be updated."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key to be added/updated.")),
    requestBody = new RequestBody(description = "Note that the input body is just the bytes of the key/cert (not the typical json), so the 'Content-Type' header must be set to 'text/plain'.", required = true, content = Array(
      new Content(
        examples = Array(
          new ExampleObject(
            value = """{
  "key": "string",
}
"""
          )
        ),
        mediaType = "application/json",
        schema = new Schema(implementation = classOf[PutServiceKeyRequest])
      )
    )),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def servicePutKeyRoute: Route = (path("orgs" / Segment / "services" / Segment / "keys" / Segment) & put) { (orgid, service, keyId) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.WRITE) { _ =>
      extractRawBodyAsStr { reqBodyAsStr =>
        val reqBody: PutServiceKeyRequest = PutServiceKeyRequest(reqBodyAsStr)
        validateWithMsg(reqBody.getAnyProblem) {
          complete({
            db.run(reqBody.toServiceKeyRow(compositeId, keyId).upsert.asTry.flatMap({
              case Success(v) =>
                // Get the value of the public field
                logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " result: " + v)
                ServicesTQ.getPublic(compositeId).result.asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(public) =>
                // Add the resource to the resourcechanges table
                logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " public field: " + public)
                if (public.nonEmpty) {
                  val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                  ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEKEYS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/keys/" + keyId + " updated in changes table: " + v)
                (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("key.added.or.updated")))
              case Failure(t: org.postgresql.util.PSQLException) =>
                if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage))
              case Failure(t) =>
                if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
                else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.key.not.inserted.or.updated", keyId, compositeId, t.getMessage)))
            })
          }) // end of complete
        } // end of validateWithMsg
      } // end of extractRawBodyAsStr
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service}/keys ===============================
  @DELETE
  @Path("{service}/keys")
  @Operation(summary = "Deletes all keys of a service", description = "Deletes all of the current keys/certs for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def serviceDeleteKeysRoute: Route = (path("orgs" / Segment / "services" / Segment / "keys") & delete) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceKeysTQ.getKeys(compositeId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /services/" + service + "/keys result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.service.keys.found", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/keys updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.keys.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.keys.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.keys.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service}/keys/{keyid} ===============================
  @DELETE
  @Path("{service}/keys/{keyid}")
  @Operation(summary = "Deletes a key of a service", description = "Deletes a key/cert for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "keyid", in = ParameterIn.PATH, description = "ID of the key.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/key")
  def serviceDeleteKeyRoute: Route = (path("orgs" / Segment / "services" / Segment / "keys" / Segment) & delete) { (orgid, service, keyId) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/keys public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceKeysTQ.getKey(compositeId, keyId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("DELETE /services/" + service + "/keys/" + keyId + " result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEKEYS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.key.not.found", keyId, compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/keys/" + keyId + " updated in changes table: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.key.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.key.not.deleted", keyId, compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.key.not.deleted", keyId, compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  //~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

  /* ====== GET /orgs/{orgid}/services/{service}/dockauths ================================ */
  @GET
  @Path("{service}/dockauths")
  @Operation(summary = "Returns all docker image tokens for this service", description = "Returns all the docker image authentication tokens for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(
            new Content(
            examples = Array(
              new ExampleObject(
                value ="""
  [
    {
      "dockAuthId": 0,
      "registry": "string",
      "username": "string",
      "token": string,
      "lastUpdated": string
    },
      ...
  ]
"""
              )
            ),
            mediaType = "application/json",
            schema = new Schema(implementation = classOf[List[ServiceDockAuth]])
          )
        )),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def serviceGetDockauthsRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths") & get) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.READ) { _ =>
      complete({
        db.run(ServiceDockAuthsTQ.getDockAuths(compositeId).result).map({ list =>
          logger.debug(s"GET /orgs/$orgid/services/$service/dockauths result size: ${list.size}")
          val code: StatusCode with Serializable = if (list.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, list.sortWith(_.dockAuthId < _.dockAuthId).map(_.toServiceDockAuth))
        })
      }) // end of complete
    } // end of exchAuth
  }

  /* ====== GET /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ================================ */
  @GET
  @Path("{service}/dockauths/{dockauthid}")
  @Operation(summary = "Returns a docker image token for this service", description = "Returns the docker image authentication token with the specified dockauthid for this service. Can be run by any credentials able to view the service.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ServiceDockAuth])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def serviceGetDockauthRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths" / Segment) & get) { (orgid, service, dockauthIdAsStr) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.READ) { _ =>
      complete({
        Try(dockauthIdAsStr.toInt) match {
          case Success(dockauthId) =>
            db.run(ServiceDockAuthsTQ.getDockAuth(compositeId, dockauthId).result).map({ list =>
              logger.debug("GET /orgs/" + orgid + "/services/" + service + "/dockauths/" + dockauthId + " result: " + list.size)
              if (list.nonEmpty) (HttpCode.OK, list.head.toServiceDockAuth)
              else (HttpCode.NOT_FOUND, list)
            })
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("dockauth.must.be.int", t.getMessage))
          case Failure(t) =>
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("dockauth.must.be.int", t.getMessage)))
        }
      }) // end of complete
    } // end of exchAuth
  }

  // =========== POST /orgs/{orgid}/services/{service}/dockauths ===============================
  @POST
  @Path("{service}/dockauths")
  @Operation(
    summary = "Adds a docker image token for the service",
    description = "Adds a new docker image authentication token for this service. As an optimization, if a dockauth resource already exists with the same service, registry, username, and token, this method will just update that lastupdated field. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(
        name = "orgid",
        in = ParameterIn.PATH,
        description = "Organization id."
      ),
      new Parameter(
        name = "service",
        in = ParameterIn.PATH,
        description = "ID of the service to be updated."
      )
    ),
    requestBody = new RequestBody(
      content = Array(
        new Content(
          examples = Array(
            new ExampleObject(
              value = """{
  "registry": "myregistry.com",
  "username": "mydockeruser",   // optional
  "token": "mydockertoken"
}
"""
            )
          ),
          mediaType = "application/json",
          schema = new Schema(implementation = classOf[PostPutServiceDockAuthRequest])
        )
      ),
      required = true
    ),
    responses = Array(
      new responses.ApiResponse(
        responseCode = "201",
        description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))
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
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def servicePostDockauthRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths") & post & entity(as[PostPutServiceDockAuthRequest])) { (orgid, service, reqBody) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem(None)) {
        complete({
          val dockAuthId = 0      // the db will choose a new id on insert
          var resultNum: Int = -1
          db.run(reqBody.getDupDockAuth(compositeId).result.asTry.flatMap({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/services" + service + "/dockauths find duplicate: " + v)
              if (v.nonEmpty) ServiceDockAuthsTQ.getLastUpdatedAction(compositeId, v.head.dockAuthId).asTry // there was a duplicate entry, so just update its lastUpdated field
              else reqBody.toServiceDockAuthRow(compositeId, dockAuthId).insert.asTry // no duplicate entry so add the one they gave us
            case Failure(t) => DBIO.failed(new Throwable(t.getMessage)).asTry
          }).flatMap({
            case Success(n) =>
              // Get the value of the public field
              logger.debug("POST /orgs/" + orgid + "/services/" + service + "/dockauths result: " + n)
              resultNum = n.asInstanceOf[Int] // num is either the id that was added, or (in the dup case) the number of rows that were updated (0 or 1)
              ServicesTQ.getPublic(compositeId).result.asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(public) =>
              // Add the resource to the resourcechanges table
              logger.debug("POST /orgs/" + orgid + "/services/" + service + "/dockauths public field: " + public)
              if (public.nonEmpty) {
                val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.CREATED).insert.asTry
              } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("POST /orgs/" + orgid + "/services/" + service + "/dockauths updated in changes table: " + v)
              resultNum match {
                case 0 => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("duplicate.dockauth.resource.already.exists"))) // we don't expect this, but it is possible, but only means that the lastUpdated field didn't get updated
                case 1 => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.resource.updated"))) //someday: this can be 2 cases i dont know how to distinguish between: A) the 1st time anyone added a dockauth, or B) a dup was found and we updated it
                case -1 => (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("dockauth.unexpected"))) // this is meant to catch the case where the resultNum variable for some reason isn't set
                case _ => (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.num.added", resultNum))) // we did not find a dup, so this is the dockauth id that was added
              }
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage)))
              else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.dockauth.not.inserted", dockAuthId, compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== PUT /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ===============================
  @PUT
  @Path("{service}/dockauths/{dockauthid}")
  @Operation(summary = "Updates a docker image token for the service", description = "Updates an existing docker image authentication token for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "ID of the service to be updated."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    requestBody = new RequestBody(description = "See the POST route for details.", required = true, content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[PostPutServiceDockAuthRequest])))),
    responses = Array(
      new responses.ApiResponse(responseCode = "201", description = "response body",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[ApiResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def servicePutDockauthRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths" / Segment) & put & entity(as[PostPutServiceDockAuthRequest])) { (orgid, service, dockauthIdAsStr, reqBody) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId),Access.WRITE) { _ =>
      validateWithMsg(reqBody.getAnyProblem(Some(dockauthIdAsStr))) {
        complete({
          val dockAuthId: Int = dockauthIdAsStr.toInt  // already checked that it is a valid int in validateWithMsg()
          db.run(reqBody.toServiceDockAuthRow(compositeId, dockAuthId).update.asTry.flatMap({
            case Success(n) =>
              // Get the value of the public field
              logger.debug("POST /orgs/" + orgid + "/services/" + service + "/dockauths result: " + n)
              val numUpdated: Int = n.asInstanceOf[Int] // n is an AnyRef so we have to do this to get it to an int
              if (numUpdated > 0) ServicesTQ.getPublic(compositeId).result.asTry
              else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.OK, ExchMsg.translate("dockauth.not.found", dockAuthId))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          }).flatMap({
            case Success(public) =>
              // Add the resource to the resourcechanges table
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/dockauths/" + dockAuthId + " public field: " + public)
              if (public.nonEmpty) {
                val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, public.head, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.CREATEDMODIFIED).insert.asTry
              } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
            case Failure(t) => DBIO.failed(t).asTry
          })).map({
            case Success(v) =>
              logger.debug("PUT /orgs/" + orgid + "/services/" + service + "/dockauths/" + dockAuthId + " updated in changes table: " + v)
              (HttpCode.PUT_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("dockauth.updated", dockAuthId)))
            case Failure(t: DBProcessingError) =>
              t.toComplete
            case Failure(t: org.postgresql.util.PSQLException) =>
              if (ExchangePosgtresErrorHandling.isAccessDeniedError(t)) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage)))
              else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage))
            case Failure(t) =>
              if (t.getMessage.startsWith("Access Denied:")) (HttpCode.ACCESS_DENIED, ApiResponse(ApiRespType.ACCESS_DENIED, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage)))
              else (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.dockauth.not.updated", dockAuthId, compositeId, t.getMessage)))
          })
        }) // end of complete
      } // end of validateWithMsg
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service}/dockauths ===============================
  @DELETE
  @Path("{service}/dockauths")
  @Operation(summary = "Deletes all docker image auth tokens of a service", description = "Deletes all of the current docker image auth tokens for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def serviceDeleteDockauthsRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths") & delete) { (orgid, service) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        var storedPublicField = false
        db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
          case Success(public) =>
            // Get the value of the public field before delete
            logger.debug("DELETE /services/" + service + "/dockauths public field: " + public)
            if (public.nonEmpty) {
              storedPublicField = public.head
              ServiceDockAuthsTQ.getDockAuths(compositeId).delete.asTry
            } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
          case Failure(t) => DBIO.failed(t).asTry
        }).flatMap({
          case Success(v) =>
            // Add the resource to the resourcechanges table
            logger.debug("POST /orgs/" + orgid + "/services result: " + v)
            if (v > 0) { // there were no db errors, but determine if it actually found it or not
              val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
              ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.DELETED).insert.asTry
            } else {
              DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("no.dockauths.found.for.service", compositeId))).asTry
            }
          case Failure(t) => DBIO.failed(t).asTry
        })).map({
          case Success(v) =>
            logger.debug("DELETE /services/" + service + "/dockauths result: " + v)
            (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.dockauths.deleted")))
          case Failure(t: DBProcessingError) =>
            t.toComplete
          case Failure(t: org.postgresql.util.PSQLException) =>
            ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauths.not.deleted", compositeId, t.toString))
          case Failure(t) =>
            (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.dockauths.not.deleted", compositeId, t.toString)))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // =========== DELETE /orgs/{orgid}/services/{service}/dockauths/{dockauthid} ===============================
  @DELETE
  @Path("{service}/dockauths/{dockauthid}")
  @Operation(summary = "Deletes a docker image auth token of a service", description = "Deletes a docker image auth token for this service. This can only be run by the service owning user.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "service", in = ParameterIn.PATH, description = "Service name."),
      new Parameter(name = "dockauthid", in = ParameterIn.PATH, description = "ID of the dockauth.")),
    responses = Array(
      new responses.ApiResponse(responseCode = "204", description = "deleted"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  @io.swagger.v3.oas.annotations.tags.Tag(name = "service/docker-authorization")
  def serviceDeleteDockauthRoute: Route = (path("orgs" / Segment / "services" / Segment / "dockauths" / Segment) & delete) { (orgid, service, dockauthIdAsStr) =>
    val compositeId: String = OrgAndId(orgid, service).toString
    exchAuth(TService(compositeId), Access.WRITE) { _ =>
      complete({
        Try(dockauthIdAsStr.toInt) match {
          case Success(dockauthId) =>
            var storedPublicField = false
            db.run(ServicesTQ.getPublic(compositeId).result.asTry.flatMap({
              case Success(public) =>
                // Get the value of the public field before delete
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " public field: " + public)
                if (public.nonEmpty) {
                  storedPublicField = public.head
                  ServiceDockAuthsTQ.getDockAuth(compositeId, dockauthId).delete.asTry
                } else DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.not.found", compositeId))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " result: " + v)
                if (v > 0) { // there were no db errors, but determine if it actually found it or not
                  val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                  ResourceChange(0L, orgid, serviceId, ResChangeCategory.SERVICE, storedPublicField, ResChangeResource.SERVICEDOCKAUTHS, ResChangeOperation.DELETED).insert.asTry
                } else {
                  DBIO.failed(new DBProcessingError(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, ExchMsg.translate("service.dockauths.not.found", dockauthId, compositeId))).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                logger.debug("DELETE /services/" + service + "/dockauths/" + dockauthId + " updated in changes table: " + v)
                (HttpCode.DELETED, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.dockauths.deleted")))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.dockauths.not.deleted", dockauthId, compositeId, t.toString))
              case Failure(t) =>
                (HttpCode.INTERNAL_ERROR, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("service.dockauths.not.deleted", dockauthId, compositeId, t.toString)))
            })
          case Failure(t) =>  // the dockauth id wasn't a valid int
            (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, "dockauthid must be an integer: " + t.getMessage))
        }
      }) // end of complete
    } // end of exchAuth
  }

}
