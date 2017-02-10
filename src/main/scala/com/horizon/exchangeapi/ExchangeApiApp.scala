/** Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package com.horizon.exchangeapi

import org.scalatra._
import slick.jdbc.PostgresProfile.api._
// import scala.concurrent.ExecutionContext.Implicits.global    // this is needed for FutureSupport
import org.scalatra.swagger._
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.scalatra.json._
import org.slf4j.{LoggerFactory, Logger}
import scala.collection.mutable.ListBuffer
import com.horizon.exchangeapi.tables._

/** Servlet for the Exchange REST API.
 *
 *  @constructor create the main servlet.
 *  @param db the database handle to access the db tables in postgres
 *  @param swagger the ExchangeApiSwagger instance, created in ScalatraBootstrap
 */
class ExchangeApiApp(val db: Database)(implicit val swagger: Swagger) extends ScalatraServlet
    with FutureSupport with NativeJsonSupport with SwaggerSupport with AuthenticationSupport with DevicesRoutes with AgbotsRoutes with UsersRoutes with AdminRoutes with BlockchainsRoutes {

  /** Sets up automatic case class to JSON output serialization, required by the JValueResult trait. */
  protected implicit val jsonFormats: Formats = DefaultFormats
  // implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  implicit val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  // A description of our application. This will show up in the Swagger docs.
  protected val applicationDescription = "The Blue Horizon Data Exchange API."

  /** Before every action runs, set the content type to be in JSON format. */
  before() {
    contentType = formats("json")
  }

  // Needed as the execution context for Futures, including db.run results
  protected implicit def executor = scala.concurrent.ExecutionContext.Implicits.global

  // Initialize authentication cache from objects in the db
  ExchConfig.createRoot(db)
  AuthCache.users.init(db)
  AuthCache.devices.init(db)
  AuthCache.agbots.init(db)
  AuthCache.bctypes.init(db)
  AuthCache.blockchains.init(db)

  // All of the route implementations are in traits called *Routes
}
