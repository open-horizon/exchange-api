package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._

import scala.concurrent.ExecutionContext
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{Content, Schema}
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations._
import com.horizon.exchangeapi.tables._
import slick.jdbc.PostgresProfile.api._

import scala.util.{Failure, Success}

// Provides routes for browsing the services and patterns in the IBM catalog
@Path("/v1/catalog")
@io.swagger.v3.oas.annotations.tags.Tag(name = "catalog")
trait CatalogRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def catalogRoutes: Route = catalogGetServicesRoute ~ catalogGetPatternsRoute ~ catalogGetServicesAll ~ catalogGetPatternsAll

  // ====== GET /catalog/services ================================
  @GET
  @Path("services")
  @Operation(summary = "Returns services in the IBM catalog", description = "Returns public service definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetServicesResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetServicesRoute: Route = (path("catalog" / "services") & get & parameter(('orgtype.?))) { (orgType) =>
    exchAuth(TService(OrgAndId("*","*").toString),Access.READ_ALL_SERVICES) { _ =>
        complete({
          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType(orgType.getOrElse("IBM")) join ServicesTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          db.run(svcQuery.result).map({ list =>
            logger.debug("GET /catalog/services result size: "+list.size)
            val services = list.map(a => a.service -> a.toService).toMap
            val code = if (services.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetServicesResponse(services, 0))
          })
        }) // end of complete
    } // end of exchAuth
  }

  // ====== GET /catalog/patterns ================================
  @GET
  @Path("patterns")
  @Operation(summary = "Returns patterns in the IBM catalog", description = "Returns public pattern definitions from orgs of the specified orgtype (default is IBM). Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgtype", in = ParameterIn.QUERY, required = false, description = "Filter results to only include orgs with this org type. A common org type is 'IBM'.",
        content = Array(new Content(schema = new Schema(implementation = classOf[String], allowableValues = Array("IBM")))))),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetPatternsResponse])))),
      new responses.ApiResponse(responseCode = "400", description = "bad input"),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetPatternsRoute: Route = (path("catalog" / "patterns") & get & parameter(('orgtype.?))) { (orgType) =>
    exchAuth(TPattern(OrgAndId("*","*").toString),Access.READ_ALL_PATTERNS) { _ =>
      complete({
        val svcQuery = for {
          (_, svc) <- OrgsTQ.getOrgidsOfType(orgType.getOrElse("IBM")) join PatternsTQ.rows on ((o, s) => {o === s.orgid && s.public})
        } yield svc

        db.run(svcQuery.result).map({ list =>
          logger.debug("GET /catalog/patterns result size: "+list.size)
          val patterns = list.map(a => a.pattern -> a.toPattern).toMap
          val code = if (patterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
          (code, GetPatternsResponse(patterns, 0))
        })
      }) // end of complete
    } // end of exchAuth
  }

  // ====== GET /catalog/{orgid}/services ================================
  @GET
  @Path("")
  @Operation(summary = "Returns all services", description = "Returns all service definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
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
        content = Array(new Content(schema = new Schema(implementation = classOf[GetServicesResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetServicesAll: Route = (path("catalog" / Segment / "services") & get & parameter(('owner.?, 'public.?, 'url.?, 'version.?, 'arch.?, 'nodetype.?, 'requiredurl.?))) { (orgid, owner, public, url, version, arch, nodetype, requiredurl) =>
    exchAuth(TService(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validateWithMsg(GetServicesUtils.getServicesProblem(public, version, nodetype)) {
        complete({
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
            val requrl2 = "%\"url\":\"" + requrl + "\"%"
            q = q.filter(_.requiredServices like requrl2)
          })

          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType("IBM") join ServicesTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          var allServices : Map[String, Service] = null
          db.run(q.result.flatMap({ list =>
            logger.debug("GET /catalog/"+orgid+"/services org result size: "+list.size)
            val services = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.service -> e.toService).toMap
            allServices = services
            svcQuery.result
          })).map({ list =>
            logger.debug("GET /catalog/"+orgid+"/services IBM result size: "+list.size)
            val services = list.map(a => a.service -> a.toService).toMap
            allServices = allServices ++ services
            val code = if (allServices.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetServicesResponse(allServices, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

  /* ====== GET /catalog/{orgid}/patterns ================================ */
  @GET
  @Path("")
  @Operation(summary = "Returns all patterns", description = "Returns all pattern definitions in this organization and in the IBM organization. Can be run by any user, node, or agbot.",
    parameters = Array(
      new Parameter(name = "orgid", in = ParameterIn.PATH, description = "Organization id."),
      new Parameter(name = "idfilter", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this id (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "owner", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this owner (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "public", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this public setting"),
      new Parameter(name = "label", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this label (can include % for wildcard - the URL encoding for % is %25)"),
      new Parameter(name = "description", in = ParameterIn.QUERY, required = false, description = "Filter results to only include patterns with this description (can include % for wildcard - the URL encoding for % is %25)")),
    responses = Array(
      new responses.ApiResponse(responseCode = "200", description = "response body",
        content = Array(new Content(schema = new Schema(implementation = classOf[GetPatternsResponse])))),
      new responses.ApiResponse(responseCode = "401", description = "invalid credentials"),
      new responses.ApiResponse(responseCode = "403", description = "access denied"),
      new responses.ApiResponse(responseCode = "404", description = "not found")))
  def catalogGetPatternsAll: Route = (path("catalog" / Segment / "patterns") & get & parameter(('idfilter.?, 'owner.?, 'public.?, 'label.?, 'description.?))) { (orgid, idfilter, owner, public, label, description) =>
    exchAuth(TPattern(OrgAndId(orgid, "*").toString), Access.READ) { ident =>
      validate(public.isEmpty || (public.get.toLowerCase == "true" || public.get.toLowerCase == "false"), ExchMsg.translate("bad.public.param")) {
        complete({
          logger.debug("ORGID: "+ orgid)
          //var q = PatternsTQ.rows.subquery
          var q = PatternsTQ.getAllPatterns(orgid)
          // If multiple filters are specified they are anded together by adding the next filter to the previous filter by using q.filter
          idfilter.foreach(id => { if (id.contains("%")) q = q.filter(_.pattern like id) else q = q.filter(_.pattern === id) })
          owner.foreach(owner => { if (owner.contains("%")) q = q.filter(_.owner like owner) else q = q.filter(_.owner === owner) })
          public.foreach(public => { if (public.toLowerCase == "true") q = q.filter(_.public === true) else q = q.filter(_.public === false) })
          label.foreach(lab => { if (lab.contains("%")) q = q.filter(_.label like lab) else q = q.filter(_.label === lab) })
          description.foreach(desc => { if (desc.contains("%")) q = q.filter(_.description like desc) else q = q.filter(_.description === desc) })

          val svcQuery = for {
            (_, svc) <- OrgsTQ.getOrgidsOfType("IBM") join PatternsTQ.rows on ((o, s) => {o === s.orgid && s.public})
          } yield svc

          var allPatterns : Map[String, Pattern] = null
          db.run(q.result.flatMap({ list =>
            logger.debug("GET /catalog/"+orgid+"/patterns org result size: "+list.size)
            val patterns = list.filter(e => ident.getOrg == e.orgid || e.public || ident.isSuperUser || ident.isMultiTenantAgbot).map(e => e.pattern -> e.toPattern).toMap
            allPatterns = patterns
            svcQuery.result
          })).map({ list =>
            logger.debug("GET /orgs/"+orgid+"/patterns IBM result size: "+list.size)
            val patterns = list.map(a => a.pattern -> a.toPattern).toMap
            allPatterns = allPatterns ++ patterns
            val code = if (allPatterns.nonEmpty) StatusCodes.OK else StatusCodes.NotFound
            (code, GetPatternsResponse(allPatterns, 0))
          })
        }) // end of complete
      } // end of validate
    } // end of exchAuth
  }

}
