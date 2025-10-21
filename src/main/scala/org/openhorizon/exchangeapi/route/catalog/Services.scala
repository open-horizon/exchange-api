package org.openhorizon.exchangeapi.route.catalog

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, parameter, path, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity2, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.GetServicesResponse
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.service.{Service, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}


@Path("/v1/catalog/services")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait Services extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /catalog/services ================================
  @GET
  @Operation(summary = "Returns services in the IBM catalog", description = "Returns public service definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(mediaType = "application/json", schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
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
        ))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def getServicesCatalog(@Parameter(hidden = true) identity: Identity2): Route =
    parameter("organization".?, "owner".?, "public".as[Boolean].optional, "url".?, "version".?, "arch".?, "nodetype".?, "requiredurl".?) {
      (organization,
       owner,
       public,
       url,
       version,
       arch,
       nodetype,
       requiredurl) =>
        logger.debug(s"GET /catalog/services?arch=${arch.getOrElse("None")},nodetype=${nodetype.getOrElse("None")},organization=${organization.getOrElse("None")},owner=${owner.getOrElse("None")},public=${"None"},requiredurl=${requiredurl.getOrElse("None")},url=${url.getOrElse("None")},version=${version.getOrElse("None")} - ${identity.resource}:${identity.role}(${identity.identifier.getOrElse("")})(${identity.owner.getOrElse("")})")
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
        
        val getServices =
          for {
            services <-
              ServicesTQ.filterIf(!identity.isSuperUser && !identity.isMultiTenantAgbot)(services => services.orgid === identity.organization || services.orgid === "IBM" || services.public)
                        .filterOpt(arch)((services, arch) => if (arch.contains('%')) services.arch like arch else services.arch === arch)
                        .filterOpt(cluster)((services, _) => services.clusterDeployment =!= "")
                        .filterOpt(device)((services, _) => services.deployment =!= "")
                        .filterOpt(organization)((services, organization) => if (organization.contains('%')) services.orgid like organization else services.orgid === organization)
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
          db.run(Compiled(getServices).result.transactionally.asTry).map {
            case Success(serviceRecords) =>
              logger.debug(s"GET /catalog/services - result size: ${serviceRecords.size}, parameters[arch:${arch}, nodetype:${nodetype}, organization:${organization}, owner:${owner}, public:${public}, requiredurl:${requiredurl}, url:${url}, version:${version}")
              
              if (serviceRecords.nonEmpty)
                (StatusCodes.OK, GetServicesResponse(serviceRecords.map(services => services._2 -> new Service(services._1)).toMap))
              else
                (StatusCodes.NotFound, GetServicesResponse())
            case Failure(exception) =>
              logger.error(cause = exception, message = s"GET /catalog/services - parameters[arch:${arch}, nodetype:${nodetype}, organization:${organization}, owner:${owner}, public:${public}, requiredurl:${requiredurl}, url:${url}, version:${version}")
              (StatusCodes.InternalServerError, ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("error")))
          }
        }
    }
  
  def servicesCatalog(identity: Identity2): Route =
    path(("catalog" / "services") | "services") {
      get {
        exchAuth(TService(OrgAndId("*","*").toString),Access.READ_ALL_SERVICES, validIdentity = identity) {
          _ =>
            getServicesCatalog(identity)
        }
      }
    }
}
