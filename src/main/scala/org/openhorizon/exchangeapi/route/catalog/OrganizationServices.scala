package org.openhorizon.exchangeapi.route.catalog

import com.github.pjfanning.pekkohttpjackson.JacksonSupport
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, ExampleObject, Schema}
import io.swagger.v3.oas.annotations.{Operation, Parameter, responses}
import jakarta.ws.rs.{GET, Path}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, path, parameter, _}
import org.apache.pekko.http.scaladsl.server.Route
import org.openhorizon.exchangeapi.auth.{Access, AuthenticationSupport, Identity, OrgAndId, TService}
import org.openhorizon.exchangeapi.route.service.{GetServicesResponse, GetServicesUtils}
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.service.{Service, ServicesTQ}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext


@Path("/v1/catalog/{organization}/services")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait OrganizationServices extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext
  
  // ====== GET /catalog/{organization}/services ================================
  @GET
  @Operation(summary = "Returns all services", description = "Returns all service definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
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
  def getOrganizationServices(identity: Identity,
                              organization: String): Route =
    parameter("owner".?,
              "public".?,
              "url".?,
              "version".?,
              "arch".?,
              "nodetype".?,
              "requiredurl".?) {
      (owner,
       public,
       url,
       version,
       arch,
       nodetype,
       requiredurl) =>
        validateWithMsg(GetServicesUtils.getServicesProblem(public, version, nodetype)) {
          complete({
            var q = ServicesTQ.getAllServices(organization)
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
            
            val svcQuery = for {
              (_, svc) <- OrgsTQ.getOrgidsOfType("IBM") join ServicesTQ on ((o, s) => {o === s.orgid && s.public})
            } yield svc
            
            var allServices : Map[String, Service] = null
            db.run(q.result.flatMap({ list =>
              logger.debug("GET /catalog/"+organization+"/services org result size: "+list.size)
              val services: Map[String, Service] = list.filter(e => identity.getOrg == e.orgid || e.public || identity.isSuperUser || identity.isMultiTenantAgbot).map(e => e.service -> e.toService).toMap
              allServices = services
              svcQuery.result
            })).map({ list =>
              logger.debug("GET /catalog/"+organization+"/services IBM result size: "+list.size)
              val services: Map[String, Service] = list.map(a => a.service -> a.toService).toMap
              allServices = allServices ++ services
              val code: StatusCode = if (allServices.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
              (code, GetServicesResponse(allServices, 0))
            })
          })
        }
    }
  
  val organizationServices: Route =
    path("catalog" / Segment / "services") {
      organization =>
        get {
          exchAuth(TService(OrgAndId(organization, "*").toString), Access.READ) {
            identity =>
              getOrganizationServices(identity, organization)
          }
        }
    }
}
