/**
 * Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package com.horizon.exchangeapi

import akka.Done
import akka.actor.{Actor, ActorSystem, Cancellable, CoordinatedShutdown, Props}
import akka.event.{Logging, LoggingAdapter}

import scala.util.matching.Regex
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry}
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.horizon.exchangeapi.tables.{AgbotMsgsTQ, NodeMsgsTQ, ResourceChangesTQ}
import org.json4s._
import slick.jdbc.TransactionIsolation.Serializable

import scala.io.Source
import scala.concurrent.duration._

// Global vals and methods
object ExchangeApi {
  // Global vals - these values are stored here instead of in ExchangeApiApp, because the latter extends DelayedInit, so the compiler checking wouldn't know when they are available. See https://stackoverflow.com/questions/36710169/why-are-implicit-variables-not-initialized-in-scala-when-called-from-unit-test/36710170
  // But putting them here and using them from here implies we have to manually verify that we set them before they are used
  var serviceHost = ""
  var servicePort = 0
  var defaultExecutionContext: ExecutionContext = _
  var defaultLogger: LoggingAdapter = _

  // Returns the exchange's version. Loading version.txt only once and then storing the value
  val versionSource = Source.fromResource("version.txt")      // returns BufferedSource
  val versionText : String = versionSource.getLines.next()
  versionSource.close()
  def adminVersion(): String = versionText
}

/* moved to config.json
object ExchangeApiConstants {
  //val serviceHost = "localhost"
  val serviceHost = "0.0.0.0"
  val servicePort = 8080
}
*/

/**
 * Main akka server for the Exchange REST API.
 */
object ExchangeApiApp extends App with OrgsRoutes with UsersRoutes with NodesRoutes with AgbotsRoutes with ServicesRoutes with PatternsRoutes with BusinessRoutes with CatalogRoutes with AdminRoutes with SwaggerUiService {

  // An example of using Spray to marshal/unmarshal json. We chose not to use it because it requires an implicit be defined for every class that needs marshalling
  //protected implicit val jsonFormats: Formats = DefaultFormats
  //implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  //import DefaultJsonProtocol._
  //implicit val apiRespJsonFormat = jsonFormat2(ApiResponse)

  // Using jackson json (un)marshalling instead of sprayjson: https://github.com/hseeberger/akka-http-json
  private implicit val formats = DefaultFormats

  // Set up ActorSystem and other dependencies here
  println(s"Running with java arguments: ${ApiUtils.getJvmArgs}")
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  //(ExchangeApi.serviceHost, ExchangeApi.servicePort) = ExchConfig.getHostAndPort  // <- scala does not support this
  ExchConfig.getHostAndPort match { case (h, p) => ExchangeApi.serviceHost = h; ExchangeApi.servicePort = p }
  //val actorConfig = ConfigFactory.parseString("akka.loglevel=" + ExchConfig.getLogLevel)
  implicit val system: ActorSystem = ActorSystem("actors", ExchConfig.getAkkaConfig)  // includes the loglevel
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  ExchangeApi.defaultExecutionContext = executionContext // need this set in an object that doesn't use DelayedInit

  implicit val logger: LoggingAdapter = Logging(system, "ExchApi")
  ExchangeApi.defaultLogger = logger // need this set in an object that doesn't use DelayedInit
  ExchConfig.createRootInCache()

  // Set a custom exception handler. See https://doc.akka.io/docs/akka-http/current/routing-dsl/exception-handling.html#exception-handling
  implicit def myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case e: java.util.concurrent.RejectedExecutionException => // this is the exception if any of the routes have trouble reaching the db during a db.run()
        //extractUri { uri =>   // in case we need the url for some reason
        //}
        val msg = if (e.getMessage != null) e.getMessage else e.toString
        complete((StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, msg)))
      case e: Exception =>
        val msg = if (e.getMessage != null) e.getMessage else e.toString
        // for now we return bad gw for any unknown exception, since that is what most of them have been
        complete((StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, msg)))
    }

  // Set a custom rejection handler. See https://doc.akka.io/docs/akka-http/current/routing-dsl/rejections.html#customizing-rejection-handling
  implicit def myRejectionHandler =
    RejectionHandler.newBuilder()
      // this handles all of our rejections
      .handle {
        case r: ExchangeRejection =>
          complete((r.httpCode, r.toApiResp))
      }
      // we never use this one, because our AuthRejection extends ExchangeRejection above
      .handle {
        case AuthorizationFailedRejection =>
          complete((StatusCodes.Forbidden, "forbidden"))
      }
      .handle {
        case ValidationRejection(msg, _) =>
          complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
      }
      // this comes from the entity() directive when parsing the request body failed
      .handle {
        case MalformedRequestContentRejection(msg, _) =>
          complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
      }
      // do not know when this is run
      .handleAll[MethodRejection] { methodRejections =>
        val names = methodRejections.map(_.supported.name)
        complete((StatusCodes.MethodNotAllowed, s"method not supported: ${names mkString " or "}"))
      }
      // this seems to be called when the route requested does not exist
      .handleNotFound { complete((StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "unrecognized route"))) }
      .result()

  // Set a custom logging of requests and responses. See https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/debugging-directives/logRequestResult.html
  val basicAuthRegex = new Regex("^Basic ?(.*)$")
  def requestResponseLogging(req: HttpRequest): RouteResult => Option[LogEntry] = {
    case RouteResult.Complete(res) =>
      // First decode the auth and get the org/id
      val optionalEncodedAuth = req.getHeader("Authorization") // this is type: com.typesafe.config.Optional[akka.http.scaladsl.model.HttpHeader]
      val encodedAuth = if (optionalEncodedAuth.isPresent) optionalEncodedAuth.get().value() else ""
      val authId = encodedAuth match {
        case "" => "<no-auth>"
        case basicAuthRegex(basicAuthEncoded) =>
          AuthenticationSupport.parseCreds(basicAuthEncoded).map(_.id).getOrElse("<invalid-auth-format>")
        case _ => "<invalid-auth-format>"
      }
      // Now log all the info
      Some(LogEntry(s"${req.uri.authority.host.address}:$authId ${req.method.name} ${req.uri}: ${res.status}", Logging.InfoLevel))
    //case Rejected(rejections) => Some(LogEntry(s"${req.method.name} ${req.uri}: rejected with: $rejections", Logging.InfoLevel)) // <- left here for when you temporarily want to see the full list of rejections that akka produces
    case Rejected(rejections) =>
      // Sometimes akka produces a bunch of MethodRejection objects (for http methods in the routes that didn't match) and then
      // TransformationRejection objects to cancel out those rejections. Filter all of these out.
      var interestingRejections = rejections.filter({
        case _: TransformationRejection => false
        case _: MethodRejection => false
        case _ => true
      })
      if (interestingRejections.isEmpty) interestingRejections = scala.collection.immutable.Seq(NotFoundRejection("unrecognized route"))
      Some(LogEntry(s"${req.method.name} ${req.uri}: rejected with: $interestingRejections", Logging.InfoLevel))
    case _ => None
  }

  // Create all of the routes and concat together
  final case class testResp(result: String)
  def testRoute = { path("test") { get { logger.debug("In /test"); complete(testResp("OK")) } } }

  //someday: use directive https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/misc-directives/selectPreferredLanguage.html to support a different language for each client
  lazy val routes: Route = DebuggingDirectives.logRequestResult(requestResponseLogging _) { pathPrefix("v1") { testRoute ~ orgsRoutes ~ usersRoutes ~ nodesRoutes ~ agbotsRoutes ~ servicesRoutes ~ patternsRoutes ~ businessRoutes ~ catalogRoutes ~ adminRoutes ~ SwaggerDocService.routes ~ swaggerUiRoutes } }

  // Load the db backend. The db access info must be in config.json
  // https://www.mchange.com/projects/c3p0/#configuration_properties
  
  var cpds: ComboPooledDataSource = new ComboPooledDataSource()
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setIdleConnectionTestPeriod(ExchConfig.getInt("api.db.idleConnectionTestPeriod"))
  cpds.setInitialPoolSize(ExchConfig.getInt("api.db.initialPoolSize"))
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setMaxConnectionAge(ExchConfig.getInt("api.db.maxConnectionAge"))
  cpds.setMaxIdleTimeExcessConnections(ExchConfig.getInt("api.db.maxIdleTimeExcessConnections"))
  cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  cpds.setMaxStatementsPerConnection(ExchConfig.getInt("api.db.maxStatementsPerConnection"))
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setNumHelperThreads(ExchConfig.getInt("api.db.numHelperThreads"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  cpds.setTestConnectionOnCheckin(ExchConfig.getBoolean("api.db.testConnectionOnCheckin"))
  cpds.setUser(ExchConfig.getString("api.db.user"))

  // maxConnections, maxThreads, and minThreads should all be the same size.
  val maxConns = ExchConfig.getInt("api.db.maxPoolSize")
  val db: Database =
    if (cpds != null) {
      Database.forDataSource(
        cpds,
        Some(maxConns),
        AsyncExecutor("ExchangeExecutor", maxConns, maxConns, ExchConfig.getInt("api.db.queueSize"), maxConns))
    } else null

  def getDb: Database = db

  system.registerOnTermination(() => db.close())

  /*
   * When we were using scalatra - left here for reference, until we investigate the CORS support in akka-http
   * before() {  // Before every action runs...
   * // We have to set these ourselves because we had to disable scalatra's builtin CorsSupport because for some inexplicable reason it doesn't set Access-Control-Allow-Origin which is critical
   * //response.setHeader("Access-Control-Allow-Origin", "*")  // <- this can only be used for unauthenticated requests
   * response.setHeader("Access-Control-Allow-Origin", request.getHeader("Origin"))
   * response.setHeader("Access-Control-Allow-Credentials", "true")
   * response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
   * //response.setHeader("Access-Control-Allow-Headers", "Cookie,Host,X-Forwarded-For,Accept-Charset,If-Modified-Since,Accept-Language,X-Forwarded-Port,Connection,X-Forwarded-Proto,User-Agent,Referer,Accept-Encoding,X-Requested-With,Authorization,Accept,Content-Type,X-Requested-With")  // this is taken from what CorsSupport sets
   * response.setHeader("Access-Control-Max-Age", "1800")
   * response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,HEAD,OPTIONS,PATCH")
   * }
   */

  // Browsers sometimes do a preflight check of this before making the real rest api call
  //options("/*"){
  //  val creds = credsForAnonymous()
  //  val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
  //  val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get      // haproxy inserts the real client ip into the header for us
  //  logger.info("User or id "+userOrId+" from "+clientIp+" running "+request.getMethod+" "+request.getPathInfo+" with request header "+request.getHeader("Access-Control-Request-Headers"))
  //}

  // Upgrade the db if necessary
  try { ExchangeApiTables.upgradeDb(db) }
  catch {
    // Handle db problems
    case timeout: java.util.concurrent.TimeoutException =>
      logger.error("Error: " + ExchMsg.translate("db.timeout.upgrading", timeout.getMessage))
      system.terminate()
    case other: Throwable =>
      logger.error("Error: " + ExchMsg.translate("db.exception.upgrading", other.getMessage))
      system.terminate()
  }

  // Initialize authentication cache from objects in the db
  AuthCache.initAllCaches(db, includingIbmAuth = true)

  /** Task for trimming `resourcechanges` table */
  def trimResourceChanges(): Unit ={
    // Get the time for trimming rows from the table
    val timeExpires = ApiTime.pastUTCTimestamp(ExchConfig.getInt("api.resourceChanges.ttl"))
    db.run(ResourceChangesTQ.getRowsExpired(timeExpires).delete.asTry).map({
      case Success(v) =>
        if (v <= 0) logger.debug("nothing to delete")
        else logger.info("resourcechanges table trimmed, number of rows deleted: " + v.toString)
      case Failure(_) => logger.error("ERROR: could not trim resourcechanges table")
    })
  }

  /** Variables and Akka Actor for trimming `resourcechanges` table */
  val Cleanup = "cleanup"
  class ChangesCleanupActor extends Actor {
    def receive = {
      case Cleanup => trimResourceChanges()
      case _ => logger.debug("invalid case sent to ChangesCleanupActor")
    }
  }
  val changesCleanupActor = system.actorOf(Props(classOf[ChangesCleanupActor]))
  var changesCleanup : Cancellable = _
  val cleanupInterval = ExchConfig.getInt("api.resourceChanges.cleanupInterval")
  logger.info("Resource changes cleanup Interval: " + cleanupInterval.toString)

  /** Task for removing expired nodemsgs and agbotmsgs */
  def removeExpiredMsgs(): Unit ={
    db.run(NodeMsgsTQ.getMsgsExpired.delete.transactionally.withTransactionIsolation(Serializable).flatMap({ xs =>
      logger.debug("nodemsgs delete expired result: "+xs.toString)
      AgbotMsgsTQ.getMsgsExpired.delete.transactionally.withTransactionIsolation(Serializable).asTry
    })).map({
      case Success(v) => logger.debug("agbotmsgs delete expired result: " + v.toString)
      case Failure(_) => logger.error("ERROR: could remove expired msgs")
    })
  }

  /** Variables and Akka Actor for removing expired nodemsgs and agbotmsgs */
  val CleanupExpiredMessages = "cleanupExpiredMessages"
  class MsgsCleanupActor extends Actor {
    def receive = {
      case CleanupExpiredMessages => removeExpiredMsgs()
      case _ => logger.debug("invalid case sent to MsgsCleanupActor")
    }
  }
  val msgsCleanupActor = system.actorOf(Props(classOf[MsgsCleanupActor]))
  var msgsCleanup : Cancellable = _
  val msgsCleanupInterval = ExchConfig.getInt("api.defaults.msgs.expired_msgs_removal_interval")
  logger.info("Remove expired msgs cleanup Interval: " + msgsCleanupInterval.toString)


  // Start serving client requests
  val serverBinding: Future[Http.ServerBinding] = Http().bindAndHandle(routes, ExchangeApi.serviceHost, ExchangeApi.servicePort)

  // Configure graceful termination. See: https://doc.akka.io/docs/akka-http/current/server-side/graceful-termination.html
  // But also see: https://discuss.lightbend.com/t/graceful-termination-on-sigterm-using-akka-http/1619
  // Note: can't test this in sbt. Instead use 'make runexecutable' and then ctrl-c
  val secondsToWait = ExchConfig.getInt("api.service.shutdownWaitForRequestsToComplete")  // ExchConfig.getAkkaConfig() also makes the akka unbind phase this long
  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "http_shutdown") { () =>
    serverBinding.flatMap({ s =>
      println(s"Exchange server unbound, waiting up to $secondsToWait seconds for in-flight requests to complete...")
      msgsCleanup.cancel()    // This cancels further deletions of node and agbot msgs
      changesCleanup.cancel()   // This cancels further Cleanups to be sent
      s.terminate(hardDeadline = secondsToWait.seconds)
    }).map { _ =>
      println("Exchange server exiting.")
      Done
    }
  }

  // When the server has initialized
  serverBinding.onComplete {
    case Success(bound) =>
      println(s"Server online at http://${bound.localAddress.getHostString}:${bound.localAddress.getPort}/")
      // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
      changesCleanup = system.scheduler.schedule(cleanupInterval.seconds, cleanupInterval.seconds, changesCleanupActor, Cleanup)
      msgsCleanup = system.scheduler.schedule(0.seconds, msgsCleanupInterval.seconds, msgsCleanupActor, CleanupExpiredMessages)
    case Failure(e) =>
      Console.err.println(s"Server could not start!")
      e.printStackTrace()
      system.terminate()
  }

  Await.result(system.whenTerminated, Duration.Inf)

  /* this is from the akka graceful termination doc, but gets run as soon as the server completes initializing. They left out the key part of how to get this invoked at the right time.
  val onceAllConnectionsTerminated: Future[Http.HttpTerminated] =
  Await.result(serverBinding, 10.seconds)
    .terminate(hardDeadline = 3.seconds)
  // when the above future completes, exit
  onceAllConnectionsTerminated.flatMap { _ => println("Exchange API exiting..."); system.terminate() } */
}

