/** Services routes for all of the /orgs/{organization}/services api methods. */
package org.openhorizon.exchangeapi.route.service

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations._
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.parameters.RequestBody
import jakarta.ws.rs.{DELETE, GET, PATCH, POST, PUT, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{as, complete, entity, get, parameter, path, post, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.openhorizon.exchangeapi.ExchangeApiApp.cacheResourceOwnership
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.table._
import org.openhorizon.exchangeapi.table.node.NodeType
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChange}
import org.openhorizon.exchangeapi.table.service.dockerauth.{ServiceDockAuth, ServiceDockAuthsTQ}
import org.openhorizon.exchangeapi.table.service.key.ServiceKeysTQ
import org.openhorizon.exchangeapi.table.service.policy.{ServicePolicy, ServicePolicyTQ}
import org.openhorizon.exchangeapi.table.service.{Service, ServiceRef, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg, ExchangePosgtresErrorHandling, HttpCode, Version, VersionRange}
import scalacache.modes.scalaFuture.mode
import slick.jdbc
import slick.jdbc.PostgresProfile.api._

import java.net.{MalformedURLException, URL}
import java.time.Instant
import java.util.UUID
import scala.collection.immutable._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util._
import scala.util.control.Breaks._

@Path("/v1/orgs/{organization}/services")
@io.swagger.v3.oas.annotations.tags.Tag(name = "service")
trait Services extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  // ====== GET /orgs/{organization}/services ================================
  @GET
  @Operation(summary = "Returns all services", description = "Returns all service definitions in this organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "organization", in = ParameterIn.PATH, description = "Organization id."),
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
  def getServices(@Parameter(hidden = true) identity: Identity2,
                  @Parameter(hidden = true) organization: String): Route =
    parameter("owner".?, "public".as[Boolean].optional, "url".?, "version".?, "arch".?, "nodetype".?, "requiredurl".?) {
      (owner, public, url, version, arch, nodetype, requiredurl) =>
        logger.debug(s"GET /orgs/${organization}/services?arch=${arch.getOrElse("None")}, nodetype=${nodetype.getOrElse("None")}, owner=${owner.getOrElse("None")}, public=${"None"}, requiredurl=${requiredurl.getOrElse("None")}, url=${url.getOrElse("None")}, version=${version.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
        
        validateWithMsg(if (version.isDefined && !Version(version.get).isValid)
                          Option(ExchMsg.translate("version.not.valid.format", version.get))
                        else if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase))
                          Option(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
                        else
                          None) {
          implicit val jsonFormats: Formats = DefaultFormats
          
          val device: Option[Boolean] =
            if (nodetype.isEmpty)
              None
            else if (nodetype.getOrElse("") == "device")
              Option(true)
            else
              None
          
          val cluster: Option[Boolean] =
            if (nodetype.isEmpty)
              None
            else if (nodetype.getOrElse("") == "cluster")
              Option(true)
            else
              None
          
          
          val getServices: Query[((Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String]), Rep[String]), ((String, String, String, String, String, String, String, String, String, String, String, String, String, Boolean, String, String, String, String, String), String), Seq] =
            for {
              services: ((Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String], Rep[Boolean], Rep[String], Rep[String], Rep[String], Rep[String], Rep[String]), Rep[String]) <-
                ServicesTQ.filter(services => services.orgid === organization)
                          .filterIf(identity.isAgbot && !identity.isMultiTenantAgbot)(services => (services.orgid === identity.organization) || (services.orgid === organization && services.public))
                          .filterIf(identity.isUser)(services => (services.orgid === identity.organization && services.owner === identity.identifier) || (services.orgid === organization && services.public))
                          .filterOpt(arch)((services, arch) => if (arch.contains('%')) services.arch like arch else services.arch === arch)
                          .filterOpt(cluster)((services, _) => services.clusterDeployment =!= "")
                          .filterOpt(device)((services, _) => services.deployment =!= "")
                          .filterOpt(public)((services, public) => services.public === public)
                          .filterOpt(requiredurl)((services, requiredurl) => services.requiredServices like "%\"url\":\"" + requiredurl + "\"%")
                          .filterOpt(url)((services, url) => if (url.contains('%')) services.url like url else services.url === url)
                          .filterOpt(version)((services, version) => if (version.contains('%')) services.version like version else services.version === version)
                          .join(UsersTQ.map(users => (users.organization, users.user, users.username)))
                          .on(_.owner === _._2)
                          .filterOpt(owner)((services, owner) => if (owner.contains('%')) (services._2._1 ++ "/" ++ services._2._3) like owner else (services._2._1 ++ "/" ++ services._2._3) === owner) // This comes after the join
                          .sortBy(services => (services._1.orgid.asc, services._1.service.asc))
                          .map(services =>
                                ((services._1.arch,
                                  services._1.clusterDeployment,
                                  services._1.clusterDeploymentSignature,
                                  services._1.deployment,
                                  services._1.deploymentSignature,
                                  services._1.description,
                                  services._1.documentation,
                                  services._1.imageStore,
                                  services._1.label,
                                  services._1.lastUpdated,
                                  services._1.matchHardware,
                                  services._1.orgid,
                                  (services._2._1 ++ "/" ++ services._2._3),  // owner
                                  services._1.public,
                                  services._1.requiredServices,
                                  services._1.sharable,
                                  services._1.url,
                                  services._1.userInput,
                                  services._1.version),
                                 services._1.service))
            } yield services
          
          complete {
            db.run(Compiled(getServices).result).map {
              serviceRecords =>
                logger.debug("GET /orgs/"+organization+"/services result size: " + serviceRecords.size)
                
                if (serviceRecords.nonEmpty)
                  (StatusCodes.OK, GetServicesResponse(serviceRecords.map(records => records._2 -> new Service(records._1)).toMap))
                else
                  (StatusCodes.NotFound, GetServicesResponse())
            }
          }
        }
    }
  
  // =========== POST /orgs/{organization}/services ===============================
  @POST
  @Operation(
    summary = "Adds a service",
    description = "A service resource contains the metadata that Horizon needs to deploy the docker images that implement this service. A service can either be an edge application, or a lower level edge service that provides access to sensors or reusable features. The service can require 1 or more other services that Horizon should also deploy when deploying this service. If public is set to true, the service can be shared across organizations. This can only be called by a user.",
    parameters = Array(
      new Parameter(
        name = "organization",
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
  "label": "Location for amd64",
  "description": "blah blah",
  "public": true,
  "documentation": "https://console.cloud.ibm.com/docs/services/edge-fabric/poc/sdr.html",
  "url": "github.com.open-horizon.examples.sdr2msghub",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "requiredServices": [
    {
      "org": "myorg",
      "url": "mydomain.com.gps",
      "version": "[1.0.0,INFINITY)",
      "arch": "amd64"
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
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "clusterDeployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\"}}}",
  "clusterDeploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "imageStore": {
    "storeType": "dockerRegistry"
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
  def postServices(@Parameter(hidden = true) identity: Identity2,
                   @Parameter(hidden = true) organization: String): Route =
    entity(as[PostPutServiceRequest]) {
      reqBody =>
        Future { logger.debug(s"POST /orgs/$organization/services - By ${identity.resource}(${identity.identifier.getOrElse(identity.owner.getOrElse("None"))}):${identity.role}") }
        validateWithMsg(reqBody.getAnyProblem(organization, null)) {
          
          val INSTANT: Instant = Instant.now()
          
          complete({
            val service: String = reqBody.formId(organization)
            val owner: Option[UUID] = identity.identifier   // currently only users are allowed to create/update services, so owner will never be blank
            
            // Make a list of service searches for the required services. This can match more services than we need, because it wildcards the version.
            // We'll look for versions within the required ranges in the db access routine below.
            val svcIds: Seq[String] = reqBody.requiredServices.getOrElse(List()).map(s => ServicesTQ.formId(s.org, s.url, "%", s.arch) )
            val svcAction = if (svcIds.isEmpty) DBIO.successful(Vector())   // no services to look for
            else {
              // The inner map() and reduceLeft() OR together all of the likes to give to filter()
              ServicesTQ.filter(s => { svcIds.map(s.service like _).reduceLeft(_ || _) }).map(s => (s.orgid, s.url, s.version, s.arch)).result
            }
            
            db.run(svcAction.asTry.flatMap({
              case Success(rows) =>
                Future { logger.debug(s"POST /orgs/$organization/services - requiredServices validation: $rows") }
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
                //logger.error(s"POST /orgs/$organization /services - L380 owner: ${owner.isDefined},    ${owner.getOrElse("None")}")
                if (invalidIndex < 0) ServicesTQ.getNumOwned(identity.identifier.getOrElse(identity.owner.get)).result.asTry // we are good, move on to the next step
                else {
                  //else DBIO.failed(new Throwable("the "+Nth(invalidIndex+1)+" referenced service in requiredServices does not exist in the exchange")).asTry
                  val errStr: String = ExchMsg.translate("req.service.not.in.exchange", invalidSvcRef.org, invalidSvcRef.url, invalidSvcRef.version, invalidSvcRef.arch)
                  DBIO.failed(new Throwable(errStr)).asTry
                }
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(num) =>
                Future { logger.debug(s"POST /orgs/$organization/services - number of services owned by $owner: $num") }
                val numOwned: Int = num
                val maxServices: Int = Configuration.getConfig.getInt("api.limits.maxServices")
                if (maxServices == 0 || maxServices >= numOwned) { // we are not sure if this is a create or update, but if they are already over the limit, stop them anyway
                  //logger.error(s"POST /orgs/$organization /services - L394 owner: ${owner.isDefined},    ${owner.getOrElse("None")}")
                  reqBody.toServiceRow(service, organization, identity.identifier.getOrElse(identity.owner.get)).insert.asTry
                }
                else DBIO.failed(new DBProcessingError(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, ExchMsg.translate("over.the.limit.of.services", maxServices))).asTry
              case Failure(t) => DBIO.failed(t).asTry
            }).flatMap({
              case Success(v) =>
                // Add the resource to the resourcechanges table
                Future { logger.debug(s"POST /orgs/$organization/services - result: $v") }
                val serviceId: String = service.substring(service.indexOf("/") + 1, service.length)
                ResourceChange(0L, organization, serviceId, ResChangeCategory.SERVICE, reqBody.public, ResChangeResource.SERVICE, ResChangeOperation.CREATED, INSTANT).insert.asTry
              case Failure(t) => DBIO.failed(t).asTry
            })).map({
              case Success(v) =>
                Future { logger.debug(s"POST /orgs/$organization/services - added to changes table: $v") }
                
                Future { cacheResourceOwnership.put(organization, service, "service")((identity.identifier.getOrElse(identity.owner.get), reqBody.public), ttl = Option(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds").seconds)) }
                
                (HttpCode.POST_OK, ApiResponse(ApiRespType.OK, ExchMsg.translate("service.created", service)))
              case Failure(t: DBProcessingError) =>
                t.toComplete
              case Failure(t: org.postgresql.util.PSQLException) =>
                if (ExchangePosgtresErrorHandling.isDuplicateKeyError(t)) (HttpCode.ALREADY_EXISTS, ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("service.already.exists", service, t.getMessage)))
                else ExchangePosgtresErrorHandling.ioProblemError(t, ExchMsg.translate("service.not.created", service, t.getMessage))
              case Failure(t) =>
                (HttpCode.BAD_INPUT, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("service.not.created", service, t.getMessage)))
            })
          })
        }
    }
  
  def services(identity: Identity2): Route =
    path(("catalog" | "orgs") / Segment / "services") {
      organization =>
        get {
          exchAuth(TService(OrgAndId(organization, "*").toString), Access.READ, validIdentity = identity) {
            _ =>
              getServices(identity, organization)
          }
        } ~
        post {
          exchAuth(TService(OrgAndId(organization, "").toString), Access.CREATE, validIdentity = identity) {
            _ =>
               postServices(identity, organization)
          }
        }
    }
}
