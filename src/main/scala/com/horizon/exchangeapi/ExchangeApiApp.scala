/** Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package com.horizon.exchangeapi

import com.horizon.exchangeapi.auth.IbmCloudAuth
import org.scalatra._
import slick.jdbc.PostgresProfile.api._
// import scala.concurrent.ExecutionContext.Implicits.global    // this is needed for FutureSupport
import org.json4s._
import org.scalatra.json._
import org.scalatra.swagger._
import org.scalatra.CorsSupport   // allow cross-domain requests. Note: this is pulled in automatically by SwaggerSupport
import org.slf4j.LoggerFactory

/** Servlet for the Exchange REST API.
 *
 *  @constructor create the main servlet.
 *  @param db the database handle to access the db tables in postgres
 *  @param swagger the ExchangeApiSwagger instance, created in ScalatraBootstrap
 */
class ExchangeApiApp(val db: Database)(implicit val swagger: Swagger) extends ScalatraServlet
    with FutureSupport with NativeJsonSupport with SwaggerSupport with CorsSupport with AuthenticationSupport with NodesRoutes with AgbotsRoutes with UsersRoutes with AdminRoutes with ServiceRoutes with PatternRoutes with OrgRoutes with BusinessRoutes {

  /** Sets up automatic case class to JSON output serialization, required by the JValueResult trait. */
  protected implicit val jsonFormats: Formats = DefaultFormats
  // implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  implicit val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  // A description of our application. This will show up in the Swagger docs.
  protected val applicationDescription = "The Blue Horizon Data Exchange API."

  /** Before every action runs, set the content type to be in JSON format. */
  before() {
    contentType = formats("json")

    // We have to set these ourselves because we had to disable scalatra's builtin CorsSupport because for some inexplicable reason it doesn't set Access-Control-Allow-Origin which is critical
    //response.setHeader("Access-Control-Allow-Origin", "*")  // <- this can only be used for unauthenticated requests
    response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"))
    response.setHeader("Access-Control-Allow-Credentials", "true")
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
    //response.setHeader("Access-Control-Allow-Headers", "Cookie,Host,X-Forwarded-For,Accept-Charset,If-Modified-Since,Accept-Language,X-Forwarded-Port,Connection,X-Forwarded-Proto,User-Agent,Referer,Accept-Encoding,X-Requested-With,Authorization,Accept,Content-Type,X-Requested-With")  // this is taken from what CorsSupport sets
    response.setHeader("Access-Control-Max-Age", "1800")
    response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,HEAD,OPTIONS,PATCH")
  }

  // Browsers sometimes do a preflight check of this before making the real rest api call
  options("/*"){
    val creds = credsForAnonymous()
    val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
    val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get      // haproxy inserts the real client ip into the header for us
    logger.info("User or id "+userOrId+" from "+clientIp+" running "+request.getMethod+" "+request.getPathInfo+" with request header "+request.getHeader("Access-Control-Request-Headers"))
  }

  // Needed as the execution context for Futures, including db.run results
  protected implicit def executor = scala.concurrent.ExecutionContext.Implicits.global

  // Initialize authentication cache from objects in the db
  try { ExchangeApiTables.upgradeDb(db) }
  catch {
    // Handle db problems
    case timeout: java.util.concurrent.TimeoutException => halt(HttpCode.GW_TIMEOUT, ApiResponse(ApiResponseType.GW_TIMEOUT, "DB timed out while upgrading it: "+timeout.getMessage))
    case other: Throwable => halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, "while upgrading the DB, the DB threw exception: "+other.getMessage))
  }
  ExchConfig.createRoot(db)
  AuthCache.users.init(db)
  AuthCache.nodes.init(db)
  AuthCache.agbots.init(db)
  AuthCache.services.init(db)
  AuthCache.patterns.init(db)
  AuthCache.business.init(db)
  IbmCloudAuth.init(db)

  // All of the route implementations are in traits called *Routes
}

class AccessDeniedException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)
class BadInputException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)
class NotFoundException(var httpCode: Int, var apiResponse: String, msg: String) extends Exception(msg)

