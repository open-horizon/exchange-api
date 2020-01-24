package com.horizon.exchangeapi

import javax.ws.rs._
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import de.heikoseeberger.akkahttpjackson._

import scala.concurrent.ExecutionContext
//import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.{ Content, Schema }
import io.swagger.v3.oas.annotations._
//import scala.concurrent.ExecutionContext.Implicits.global
import com.horizon.exchangeapi.tables._
//import org.json4s._
//import scala.collection.immutable._
//import scala.util._

//import com.horizon.exchangeapi.tables._
import slick.jdbc.PostgresProfile.api._

//import scala.collection.mutable.{HashMap => MutableHashMap}

// Provides routes for browsing the services and patterns in the IBM catalog
@Path("/v1/catalog")
trait CatalogRoutes extends JacksonSupport with AuthenticationSupport {
  // Will pick up these values when it is mixed in with ExchangeApiApp
  def db: Database
  def system: ActorSystem
  def logger: LoggingAdapter
  implicit def executionContext: ExecutionContext

  def catalogRoutes: Route = catalogGetServicesRoute ~ catalogGetPatternsRoute

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

}
