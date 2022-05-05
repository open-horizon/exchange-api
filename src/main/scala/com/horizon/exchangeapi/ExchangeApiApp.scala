/**
 * Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package com.horizon.exchangeapi

import java.sql.Timestamp
import java.util.Optional
import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, CoordinatedShutdown, Props, Timers}
import akka.event.{Logging, LoggingAdapter}
import akka.http.javadsl.model.HttpHeader

import scala.util.matching.Regex
import akka.http.scaladsl.server.RouteResult.Rejected
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry}
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `must-revalidate`, `no-cache`, `no-store`}
import akka.http.scaladsl.model.headers.{RawHeader, `Cache-Control`}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.{ActorMaterializer, Materializer}
import com.horizon.exchangeapi.tables
import com.horizon.exchangeapi.tables.{AgbotMsgsTQ, NodeMsgsTQ, ResourceChangesTQ}
import org.json4s._
import slick.jdbc.TransactionIsolation.Serializable

import java.io.{FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}
import scala.io.{BufferedSource, Source}
import scala.concurrent.duration._

// Global vals and methods
object ExchangeApi {
  // Global vals - these values are stored here instead of in ExchangeApiApp, because the latter extends DelayedInit, so the compiler checking wouldn't know when they are available. See https://stackoverflow.com/questions/36710169/why-are-implicit-variables-not-initialized-in-scala-when-called-from-unit-test/36710170
  // But putting them here and using them from here implies we have to manually verify that we set them before they are used
  var serviceHost = ""
  var servicePortEncrypted: Option[Int] = scala.None
  var servicePortUnencrypted: Option[Int] = scala.None
  var defaultExecutionContext: ExecutionContext = _
  var defaultLogger: LoggingAdapter = _

  // Returns the exchange's version. Loading version.txt only once and then storing the value
  val versionSource: BufferedSource = Source.fromResource("version.txt")      // returns BufferedSource
  val versionText : String = versionSource.getLines().next()
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
object ExchangeApiApp extends App
  with AgentConfigurationManagementRoutes
  with AdminRoutes
  with AgbotsRoutes
  with BusinessRoutes
  with CatalogRoutes
  with ManagementPoliciesRoutes
  with NodesRoutes
  with OrgsRoutes
  with PatternsRoutes
  with ServicesRoutes
  with SwaggerUiService
  with UsersRoutes {

  // An example of using Spray to marshal/unmarshal json. We chose not to use it because it requires an implicit be defined for every class that needs marshalling
  //protected implicit val jsonFormats: Formats = DefaultFormats
  //implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  //import DefaultJsonProtocol._
  //implicit val apiRespJsonFormat = jsonFormat2(ApiResponse)

  // Using jackson json (un)marshalling instead of sprayjson: https://github.com/hseeberger/akka-http-json
  private implicit val formats: DefaultFormats.type = DefaultFormats

  // Set up ActorSystem and other dependencies here
  println(s"Running with java arguments: ${ApiUtils.getJvmArgs}")
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  //(ExchangeApi.serviceHost, ExchangeApi.servicePort) = ExchConfig.getHostAndPort  // <- scala does not support this
  ExchConfig.getHostAndPort match {
    case (h, pe, pu) =>
      ExchangeApi.serviceHost = h
      ExchangeApi.servicePortEncrypted = pe
      ExchangeApi.servicePortUnencrypted = pu
  }
  //val actorConfig = ConfigFactory.parseString("akka.loglevel=" + ExchConfig.getLogLevel)
  implicit val system: ActorSystem = ActorSystem("actors", ExchConfig.getAkkaConfig)  // includes the loglevel
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
        val msg: String = if (e.getMessage != null) e.getMessage else e.toString
        complete((StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, msg)))
      case e: Exception =>
        val msg: String = if (e.getMessage != null) e.getMessage else e.toString
        // for now we return bad gw for any unknown exception, since that is what most of them have been
        complete((StatusCodes.BadGateway, ApiResponse(ApiRespType.BAD_GW, msg)))
    }

  // Set a custom rejection handler. See https://doc.akka.io/docs/akka-http/current/routing-dsl/rejections.html#customizing-rejection-handling
  implicit def myRejectionHandler: RejectionHandler =
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
        val names: Seq[String] = methodRejections.map(_.supported.name)
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
      val optionalEncodedAuth: Optional[HttpHeader] = req.getHeader("Authorization")
      val encodedAuth: String = if (optionalEncodedAuth.isPresent) optionalEncodedAuth.get().value() else ""
      val authId: String = encodedAuth match {
        case "" => "<no-auth>"
        case basicAuthRegex(basicAuthEncoded) =>
          AuthenticationSupport.parseCreds(basicAuthEncoded).fold("<invalid-auth-format>")(_.id)
        case _ => "<invalid-auth-format>"
      }
      // Now log all the info
      Option(LogEntry(s"${req.uri.authority.host.address}:$authId ${req.method.name} ${req.uri}: ${res.status}", Logging.InfoLevel))
    //case Rejected(rejections) => Some(LogEntry(s"${req.method.name} ${req.uri}: rejected with: $rejections", Logging.InfoLevel)) // <- left here for when you temporarily want to see the full list of rejections that akka produces
    case Rejected(rejections) =>
      // Sometimes akka produces a bunch of MethodRejection objects (for http methods in the routes that didn't match) and then
      // TransformationRejection objects to cancel out those rejections. Filter all of these out.
      var interestingRejections: Seq[Rejection] = rejections.filter({
        case _: TransformationRejection => false
        case _: MethodRejection => false
        case _ => true
      })
      if (interestingRejections.isEmpty) interestingRejections = scala.collection.immutable.Seq(NotFoundRejection("unrecognized route"))
      Option(LogEntry(s"${req.method.name} ${req.uri}: rejected with: $interestingRejections", Logging.InfoLevel))
    case _ => scala.None
  }

  // Create all of the routes and concat together
  final case class testResp(result: String = "OK")
  
  def testRoute = {
    path("test") {
      get {
        logger.debug("In /test")
        
        complete(testResp())
      }
    }
  }
  
  //someday: use directive https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/misc-directives/selectPreferredLanguage.html to support a different language for each client
  lazy val routes: Route =
    DebuggingDirectives.logRequestResult(requestResponseLogging _) {
      pathPrefix("v1") {
        respondWithDefaultHeaders(`Cache-Control`(Seq(`max-age`(0), `must-revalidate`, `no-cache`, `no-store`)),
                                  // RawHeader("Content-Type", "application/json"/*; charset=UTF-8"*/),
                                  RawHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload"), // 2 years
                                  RawHeader("X-Content-Type-Options", "nosniff"),
                                  RawHeader("X-XSS-Protection", "1; mode=block")) {
          handleExceptions(myExceptionHandler) {
            handleRejections(myRejectionHandler) {
              agentConfigurationManagementRoutes ~
              adminRoutes ~
              agbotsRoutes ~
              businessRoutes ~
              catalogRoutes ~
              managementPoliciesRoutes ~
              nodesRoutes ~
              orgsRoutes ~
              patternsRoutes ~
              servicesRoutes ~
              SwaggerDocService.routes ~
              swaggerUiRoutes ~
              testRoute ~
              usersRoutes
            }
          }
        }
      }
    }
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
  val maxConns: Int = ExchConfig.getInt("api.db.maxPoolSize")
  val db: Database =
    if (cpds != null) {
      Database.forDataSource(
        cpds,
        Option(maxConns),
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
      logger.error("Error: DB timed out while upgrading it: "+ timeout.getMessage)
      system.terminate()
    case other: Throwable =>
      logger.error("Error: while upgrading the DB, the DB threw exception: "+ other.getMessage)
      system.terminate()
  }

  // Initialize authentication cache from objects in the db
  AuthCache.initAllCaches(db, includingIbmAuth = true)

  /** Task for trimming `resourcechanges` table */
  def trimResourceChanges(): Unit ={
    // Get the time for trimming rows from the table
    val timeExpires: Timestamp = ApiTime.pastUTCTimestamp(ExchConfig.getInt("api.resourceChanges.ttl"))
    db.run(ResourceChangesTQ.getRowsExpired(timeExpires).delete.asTry).map({
      case Success(v) =>
        if (v <= 0) logger.debug("No resource changes to trim")
        else logger.info("resourcechanges table trimmed, number of records deleted: " + v.toString)
      case Failure(_) => logger.error("ERROR: could not trim resourcechanges table")
    })
  }

  /** Variables and Akka Actor for trimming `resourcechanges` table */
  val Cleanup = "cleanup";
  class ChangesCleanupActor(timerInterval: Int = ExchConfig.getInt("api.resourceChanges.cleanupInterval")) extends Actor with Timers{
    override def preStart(): Unit = {
      timers.startTimerAtFixedRate(interval = timerInterval.seconds, key = "trimResourceChanges", msg = Cleanup)
      logger.info("Scheduling change record cleanup every "+ timerInterval.seconds + " seconds")
      super.preStart()
    }
    
    override def receive: Receive = {
      case Cleanup => trimResourceChanges()
      case _ => logger.debug("invalid case sent to ChangesCleanupActor")
    }
  }
  val changesCleanupActor: ActorRef = system.actorOf(Props(new ChangesCleanupActor()))

  /** Task for removing expired nodemsgs and agbotmsgs */
  def removeExpiredMsgs(): Unit ={
    db.run(NodeMsgsTQ.getMsgsExpired.delete.transactionally.withTransactionIsolation(Serializable).asTry).map({
      case Success(v) => logger.debug("nodemsgs delete expired result: "+ v.toString)
      case Failure(_) => logger.error("ERROR: could not remove expired node msgs")
    })
    db.run(AgbotMsgsTQ.getMsgsExpired.delete.transactionally.withTransactionIsolation(Serializable).asTry).map({
      case Success(v) => logger.debug("agbotmsgs delete expired result: " + v.toString)
      case Failure(_) => logger.error("ERROR: could not remove expired agbot msgs")
    })
  }

  /** Variables and Akka Actor for removing expired nodemsgs and agbotmsgs */
  val CleanupExpiredMessages = "cleanupExpiredMessages"
  
  class MsgsCleanupActor(timerInterval: Int = ExchConfig.getInt("api.defaults.msgs.expired_msgs_removal_interval")) extends Actor with Timers {
    override def preStart(): Unit = {
      timers.startSingleTimer(key = "removeExpiredMsgsOnStart", msg = CleanupExpiredMessages, timeout = 0.seconds)
      timers.startTimerAtFixedRate(interval = timerInterval.seconds, key = "removeExpiredMsgs", msg = CleanupExpiredMessages)
      logger.info("Scheduling Agreement Bot message and Node message cleanup every "+ timerInterval.seconds + " seconds")
      super.preStart()
    }
    
    override def receive: Receive = {
      case CleanupExpiredMessages => removeExpiredMsgs()
      case _ => logger.debug("invalid case sent to MsgsCleanupActor")
    }
  }
  val msgsCleanupActor: ActorRef = system.actorOf(Props(new MsgsCleanupActor()))
  val secondsToWait: Int = ExchConfig.getInt("api.service.shutdownWaitForRequestsToComplete") // ExchConfig.getAkkaConfig() also makes the akka unbind phase this long
  
  var serverBindingHttp: Option[Http.ServerBinding] = None
  var serverBindingHttps: Option[Http.ServerBinding] = None
  
  val truststore: Option[String] =
    try {
      Option(ExchConfig.getString("api.tls.truststore"))
    }
    catch {
      case _: Exception => None
    }
  
  if(truststore.isDefined) {
    val keyStore: KeyStore = KeyStore.getInstance("PKCS12")
    val keyManager: KeyManagerFactory = KeyManagerFactory.getInstance("PKIX")
    val sslContext: SSLContext = SSLContext.getInstance("TLSv1.3")
    val trustManager: TrustManagerFactory = TrustManagerFactory.getInstance("PKIX")
    
    try {
      keyStore.load(new FileInputStream(truststore.get),
                                        ExchConfig.getString("api.tls.password").toCharArray)
      keyManager.init(keyStore, ExchConfig.getString("api.tls.password").toCharArray)
      trustManager.init(keyStore)
      sslContext.init(keyManager.getKeyManagers,
                      trustManager.getTrustManagers,
                      new SecureRandom)
  
      // Start serving client requests
      Http().newServerAt(ExchangeApi.serviceHost, ExchangeApi.servicePortEncrypted.get)
            .enableHttps(ConnectionContext.httpsServer(() => {               // Custom TLS parameters
              val engine: SSLEngine = sslContext.createSSLEngine()
              
              engine.setEnabledProtocols(Array("TLSv1.3", "TLSv1.2"))                       // TLSv1.2 is in support of OpenShift 4.6. HAPoxy router is built on top of RHEL7 which does not support TLSv1.3.
              engine.setEnabledCipherSuites(Array("TLS_AES_256_GCM_SHA384",
                                                  "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                                                  "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"))   // "TLS_CHACHA20_POLY1305_SHA256" available in Java 14
              engine.setUseClientMode(false)
              engine
            }))
            .bind(routes)
            .map(_.addToCoordinatedShutdown(hardTerminationDeadline = secondsToWait.seconds))
            .onComplete {
              case Success(binding) =>
                logger.info("Server online at "+ s"https://${ExchangeApi.serviceHost}:${binding.localAddress.getPort}/") // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
                serverBindingHttps = Option(binding)
              case Failure(e) =>
                logger.error("HTTPS server could not start!")
                e.printStackTrace()
                system.terminate()
            }
    }
    catch {
      case pkcs12NotFound: java.io.FileNotFoundException =>
        logger.error("TLS PKCS #12 "+ pkcs12NotFound.getMessage+ " not found on the filesystem")
        system.terminate()
      case _: java.io.IOException =>
        logger.error("TLS PKCS #12 password was incorrect")
        system.terminate()
    }
    
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "https-unbound") {
      () =>
        logger.info(s"Exchange HTTPS server unbound, waiting up to $secondsToWait seconds for in-flight requests to complete...")
        Future {Done}
    }
  }
  
  if(ExchangeApi.servicePortUnencrypted.isDefined) {
    Http().newServerAt(ExchangeApi.serviceHost, ExchangeApi.servicePortUnencrypted.get)
          .bind(routes)
          .map(_.addToCoordinatedShutdown(hardTerminationDeadline = secondsToWait.seconds))
          .onComplete {
            case Success(binding) =>
              logger.info("Server online at "+ s"http://${ExchangeApi.serviceHost}:${binding.localAddress.getPort}/") // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
              serverBindingHttp = Option(binding)
            case Failure(e) =>
              logger.error("HTTP server could not start!")
              e.printStackTrace()
              system.terminate()
          }
    
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "http-unbound") {
      () =>
        logger.info(s"Exchange HTTP server unbound, waiting up to $secondsToWait seconds for in-flight requests to complete...")
        Future {Done}
    }
  }
  
  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "exchange-exit") {
    () =>
      logger.info("Exchange server exiting.")
      Future {Done}
  }
  
  Await.result(system.whenTerminated, Duration.Inf)
}
