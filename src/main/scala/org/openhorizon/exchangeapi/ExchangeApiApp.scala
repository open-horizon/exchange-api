/**
 * Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package org.openhorizon.exchangeapi

import com.google.common.cache.{Cache, CacheBuilder}
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import org.openhorizon.exchangeapi.route.administration.{ClearAuthCache, Configuration, DropDatabase, InitializeDatabase, OrganizationStatus, Reload, Status, Version}
import org.openhorizon.exchangeapi.route.agreement.Confirm
import org.openhorizon.exchangeapi.route.agreementbot.{AgreementBot, AgreementBots, DeploymentPattern, DeploymentPatterns, DeploymentPolicies, DeploymentPolicy, Heartbeat}
import org.openhorizon.exchangeapi.table
import org.openhorizon.exchangeapi.table.{ExchangeApiTables, ExchangePostgresProfile}
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigSyntax, ConfigValue}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, CoordinatedShutdown, Props, Timers}
import org.apache.pekko.event.{Logging, LoggingAdapter}
import org.apache.pekko.http.caching.LfuCache
import org.apache.pekko.http.caching.scaladsl.{Cache, CachingSettings}
import org.apache.pekko.http.cors.scaladsl.CorsDirectives._
import org.apache.pekko.http.cors.scaladsl.CorsRejection
import org.apache.pekko.http.javadsl.model.HttpHeader
import org.apache.pekko.http.javadsl.server.CustomRejection
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.{HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `must-revalidate`, `no-cache`, `no-store`}
import org.apache.pekko.http.scaladsl.model.headers.{RawHeader, `Cache-Control`}
import org.apache.pekko.http.scaladsl.server.{AuthenticationFailedRejection, AuthorizationFailedRejection, CircuitBreakerOpenRejection, ExceptionHandler, ExpectedWebSocketRequestRejection, InvalidOriginRejection, MalformedQueryParamRejection, MalformedRequestContentRejection, MethodRejection, MissingAttributeRejection, MissingCookieRejection, MissingFormFieldRejection, MissingHeaderRejection, Rejection, RejectionHandler, RejectionWithOptionalCause, RequestEntityExpectedRejection, Route, RouteResult, SchemeRejection, TooManyRangesRejection, TransformationRejection, ValidationRejection}
import org.apache.pekko.http.scaladsl.server.RouteResult.Rejected
import org.apache.pekko.http.scaladsl.server.directives.{Credentials, DebuggingDirectives, LogEntry}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.json4s._
import org.openhorizon.exchangeapi.SwaggerDocService.complete
import org.openhorizon.exchangeapi.auth.AuthCache.logger
import org.openhorizon.exchangeapi.auth.{AuthCache, AuthRoles, AuthenticationSupport, DbConnectionException, IAgbot, INode, IUser, IdNotFoundForAuthorizationException, Identity, Identity2, InvalidCredentialsException, Password, Token}
import org.openhorizon.exchangeapi.route.administration.dropdatabase.Token
import org.openhorizon.exchangeapi.route.agent.AgentConfigurationManagement
import org.openhorizon.exchangeapi.route.agreementbot.agreement.{Agreement, Agreements}
import org.openhorizon.exchangeapi.route.agreementbot.message.{Message, Messages}
import org.openhorizon.exchangeapi.route.catalog.OrganizationDeploymentPatterns
import org.openhorizon.exchangeapi.route.deploymentpattern.{DeploymentPatterns, Search}
import org.openhorizon.exchangeapi.route.deploymentpolicy.{DeploymentPolicy, DeploymentPolicySearch}
import org.openhorizon.exchangeapi.route.managementpolicy.{ManagementPolicies, ManagementPolicy}
import org.openhorizon.exchangeapi.route.node.managementpolicy.Statuses
import org.openhorizon.exchangeapi.route.node.{ConfigurationState, Details, Errors, Node, Nodes}
import org.openhorizon.exchangeapi.route.nodegroup.{NodeGroup, NodeGroups}
import org.openhorizon.exchangeapi.route.organization.{Changes, Cleanup, MaxChangeId, MyOrganizations, Organization, Organizations}
import org.openhorizon.exchangeapi.route.search.{NodeError, NodeErrors, NodeHealth, NodeService}
import org.openhorizon.exchangeapi.route.service.dockerauth.{DockerAuth, DockerAuths}
import org.openhorizon.exchangeapi.route.service.key.{Key, Keys}
import org.openhorizon.exchangeapi.route.service.{Policy, Service, Services}
import org.openhorizon.exchangeapi.route.user.{ChangePassword, Confirm, User, Users}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.AgbotMsgsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.managementpolicy.ManagementPoliciesTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.service.ServicesTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, ApiUtils, Configuration, DatabaseConnection, ExchMsg, ExchangeRejection, NotFoundRejection}
import scalacache.Entry
import scalacache.guava.GuavaCache
import scalacache.modes.scalaFuture._
import slick.jdbc.TransactionIsolation.Serializable

import java.io.{File, FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import java.sql.Timestamp
import java.util
import java.util.concurrent.TimeUnit
import java.util.{Map, Optional, UUID}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.io.{BufferedSource, Source}
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.matching.Regex
import scala.util.Properties
import scala.util.{Failure, Success}

// Global vals and methods
object ExchangeApi {
  // Global vals - these values are stored here instead of in ExchangeApiApp, because the latter extends DelayedInit, so the compiler checking wouldn't know when they are available. See https://stackoverflow.com/questions/36710169/why-are-implicit-variables-not-initialized-in-scala-when-called-from-unit-test/36710170
  // But putting them here and using them from here implies we have to manually verify that we set them before they are used
  var defaultExecutionContext: ExecutionContext = _
  var defaultLogger: LoggingAdapter = _

  // Returns the exchange's version. Loading version.txt only once and then storing the value
  val versionSource: BufferedSource = Source.fromResource("version.txt")      // returns BufferedSource
  val versionText : String = versionSource.getLines().next()
  versionSource.close()
  def adminVersion(): String = versionText
}


/**
 * Main pekko server for the Exchange REST API.
 */
object ExchangeApiApp extends App
  with AgentConfigurationManagement
  with Agreement
  with org.openhorizon.exchangeapi.route.node.agreement.Agreement
  with AgreementBot
  with AgreementBots
  with Agreements
  with org.openhorizon.exchangeapi.route.node.agreement.Agreements
  with ChangePassword
  with Changes
  with Cleanup
  with ClearAuthCache
  with org.openhorizon.exchangeapi.route.agreement.Confirm
  with org.openhorizon.exchangeapi.route.user.Confirm
  with Configuration
  with ConfigurationState
  with org.openhorizon.exchangeapi.route.agreementbot.DeploymentPattern
  with org.openhorizon.exchangeapi.route.deploymentpattern.DeploymentPattern
  with org.openhorizon.exchangeapi.route.agreementbot.DeploymentPatterns
  with org.openhorizon.exchangeapi.route.catalog.DeploymentPatterns
  with org.openhorizon.exchangeapi.route.deploymentpattern.DeploymentPatterns
  with org.openhorizon.exchangeapi.route.agreementbot.DeploymentPolicy
  with org.openhorizon.exchangeapi.route.deploymentpolicy.DeploymentPolicy
  with org.openhorizon.exchangeapi.route.agreementbot.DeploymentPolicies
  with org.openhorizon.exchangeapi.route.deploymentpolicy.DeploymentPolicies
  with DeploymentPolicySearch
  with Details
  with DockerAuth
  with DockerAuths
  with DropDatabase
  with Errors
  with org.openhorizon.exchangeapi.route.agreementbot.Heartbeat
  with org.openhorizon.exchangeapi.route.node.Heartbeat
  with InitializeDatabase
  with org.openhorizon.exchangeapi.route.deploymentpattern.key.Key
  with org.openhorizon.exchangeapi.route.service.key.Key
  with org.openhorizon.exchangeapi.route.deploymentpattern.key.Keys
  with org.openhorizon.exchangeapi.route.service.key.Keys
  with ManagementPolicies
  with ManagementPolicy
  with MaxChangeId
  with Message
  with org.openhorizon.exchangeapi.route.node.message.Message
  with Messages
  with org.openhorizon.exchangeapi.route.node.message.Messages
  with MyOrganizations
  with Node
  with org.openhorizon.exchangeapi.route.nodegroup.node.Node
  with NodeError
  with NodeErrors
  with org.openhorizon.exchangeapi.route.deploymentpattern.NodeHealth
  with org.openhorizon.exchangeapi.route.search.NodeHealth
  with Nodes
  with NodeService
  with NodeGroup
  with NodeGroups
  with Organization
  with OrganizationDeploymentPatterns
  with Organizations
  // with OrganizationServices
  with OrganizationStatus
  with org.openhorizon.exchangeapi.route.node.Policy
  with org.openhorizon.exchangeapi.route.service.Policy
  with Reload
  with Search
  with Service
  with org.openhorizon.exchangeapi.route.catalog.Services
  with org.openhorizon.exchangeapi.route.service.Services
  with org.openhorizon.exchangeapi.route.administration.Status
  with org.openhorizon.exchangeapi.route.node.managementpolicy.Status
  with org.openhorizon.exchangeapi.route.node.Status
  with org.openhorizon.exchangeapi.route.organization.Status
  with Statuses
  with SwaggerUiService
  with Token
  with User
  with Users
  with Version {
  
  // An example of using Spray to marshal/unmarshal json. We chose not to use it because it requires an implicit be defined for every class that needs marshalling
  //protected implicit val jsonFormats: Formats = DefaultFormats
  //implicit val formats = Serialization.formats(NoTypeHints)     // needed for serializing the softwareVersions map to a string (and back)
  //import DefaultJsonProtocol._
  //implicit val apiRespJsonFormat = jsonFormat2(ApiResponse)

  // Using jackson json (un)marshalling instead of sprayjson: https://github.com/hseeberger/pekko-http-json
  private implicit val formats: DefaultFormats.type = DefaultFormats

  // Set up ActorSystem and other dependencies here
  println(s"Running with java arguments: ${ApiUtils.getJvmArgs}")

  
  //val actorConfig = ConfigFactory.parseString("pekko.loglevel=" + ExchConfig.getLogLevel)
  //implicit val system: ActorSystem = ActorSystem("actors", ExchConfig.getAkkaConfig)
  //implicit val system: ActorSystem = ActorSystem("actors", ConfigFactory.parseFile(new File("/etc/horizon/exchange/config.json"), ConfigParseOptions.defaults().setSyntax(ConfigSyntax.JSON).setAllowMissing(false)).withFallback(ConfigFactory.load("application")))
  
  implicit val system: ActorSystem = ActorSystem("actors", ConfigFactory.load("exchange")) // includes the loglevel
  
  
  
  implicit val executionContext: ExecutionContext = system.dispatcher
  ExchangeApi.defaultExecutionContext = executionContext // need this set in an object that doesn't use DelayedInit

  implicit val logger: LoggingAdapter = Logging(system, "Exchange")
  ExchangeApi.defaultLogger = logger // need this set in an object that doesn't use DelayedInit
  
  AuthCache.createRootInCache()
  
  // Check common overwritten pekko configuration parameters
  logger.debug("pekko.corrdinated-shutdown:  " + system.settings.config.getConfig("pekko.coordinated-shutdown").toString)
  logger.debug("pekko.loglevel: " + system.settings.config.getString("pekko.loglevel"))
  logger.debug("pekko.http.parsing: " + system.settings.config.getConfig("pekko.http.parsing").toString)
  logger.debug("pekko.http.server: " + system.settings.config.getConfig("pekko.http.server").toString)
  
  // Set a custom exception handler. See https://doc.pekko.io/docs/pekko-http/current/routing-dsl/exception-handling.html#exception-handling
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

  // Set a custom rejection handler. See https://doc.pekko.io/docs/pekko-http/current/routing-dsl/rejections.html#customizing-rejection-handling
  implicit def myRejectionHandler: RejectionHandler =
    RejectionHandler.newBuilder()
                    // this handles all of our rejections
                    .handle {
                      case r: ExchangeRejection =>
                        complete((r.httpCode, r.toApiResp))
                    }
                    .handle {
                      case ValidationRejection(msg, _) =>
                        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
                    }
                    .handle {
                      // this comes from the entity() directive when parsing the request body failed
                      case MalformedRequestContentRejection(msg, _) =>
                        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, msg)))
                    }
                    .handle {
                      case MalformedQueryParamRejection(parameterName, errorMsg, _) =>
                        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, ExchMsg.translate("invalid.int.for.name", parameterName))))
                    }
                    // do not know when this is run
                    // .handleAll[MethodRejection] { methodRejections =>
                    //   val names: Seq[String] = methodRejections.map(_.supported.name)
                    //   complete((StatusCodes.MethodNotAllowed, s"method not supported: ${names mkString " or "}"))
                    // }
                    // this seems to be called when the route requested does not exist
                    .handleNotFound { complete((StatusCodes.NotFound, ApiResponse(ApiRespType.NOT_FOUND, "unrecognized route"))) }
                    .result()

  // Set a custom logging of requests and responses. See https://doc.pekko.io/docs/pekko-http/current/routing-dsl/directives/debugging-directives/logRequestResult.html
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
      Option(LogEntry(s"${req.uri.authority.host.address}:$authId ${req.method.name} ${req.uri}: ${res.status}", Logging.DebugLevel))
    //case Rejected(rejections) => Some(LogEntry(s"${req.method.name} ${req.uri}: rejected with: $rejections", Logging.InfoLevel)) // <- left here for when you temporarily want to see the full list of rejections that pekko produces
    case Rejected(rejections) =>
      // Sometimes pekko produces a bunch of MethodRejection objects (for http methods in the routes that didn't match) and then
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
        complete(testResp())
      }
    }
  }
  
  def getPassword(organization: String,
                  resource: String,
                  username: String): Option[(String, Int)] = {
    val fetchResourcePassword =
      for {
        resourcePassword <-
          (NodesTQ.filter(nodes => nodes.orgid === organization &&
                                   nodes.id === resource)
                  .map(nodes => (nodes.token, 0)) ++
           AgbotsTQ.filter(agbots => agbots.orgid === organization &&
                                     agbots.id === resource)
                   .map(agbots => (agbots.token, 1)) ++
           UsersTQ.filter(users => users.organization === organization &&
                                   users.username === username)
                  .map(users => (users.password.getOrElse(""), 2)))
            .sortBy(_._2.asc)
            .take(1)
            .result
      } yield resourcePassword.headOption
      
    Await.result(db.run(fetchResourcePassword.transactionally), 10.seconds)
  }
  
  val reg: Regex = """^(\S*?)/(\S*)$""".r
  
  
  // TODO: Basic authentication idea
  def myUserPassAuthenticator(credentials: Credentials): Future[Option[Identity2]] = {
    credentials match {
      case p@Credentials.Provided(resource) =>
        Future {
          val (organization: String,
               username: String) = {
            // logger.debug(s"line 323    resource:$resource")
            resource match {
              case reg(organization, username) =>
                (organization, username)
              case _ =>
                ("", "")
            }
          }
          
          if (organization.isEmpty &&
              username.isEmpty)
            Future.successful(None)
          
          val (storedSecret: String, userType: Int) = getPassword(organization, resource, username).getOrElse(("", 0))
          
          if (p.provideVerify(secret = storedSecret,
                              verifier = (storedSecret, requestPassword) =>  // (Hashed credential in database, Plain-text password from Authorization: Basic header)
                              {/*logger.debug("Line 333:    credential: " + requestPassword + "    secret: " + storedSecret);*/ Password.check(requestPassword, storedSecret)})) {
            userType match { // Hack. Non-unified identity currently.
              case 0 =>
                Some(Identity2(organization = organization,
                               role = AuthRoles.Node,
                               username = username))
              case 1 =>
                Some(Identity2(organization = organization,
                               role = AuthRoles.Agbot,
                               username = username))
              case 2 =>
                val useridentity: (Boolean, Boolean, UUID) =
                  Await.result(db.run(
                    Compiled(UsersTQ.filter(users => users.organization === organization &&
                                                     users.username === username)
                                    .map(users => (users.isHubAdmin,
                                                   users.isOrgAdmin,
                                                   users.user))
                                    .take(1))
                                    .result
                                    .head
                                    .transactionally),
                               10.seconds)
                val authenticatedUserIdentity =
                  Some(Identity2(identifier = Option(useridentity._3),
                                 organization = organization,
                                 role =
                                   useridentity match {
                                     case (true, true, _) =>
                                       if ((s"$organization/$username" != "root/root"))
                                         logger.warning(s"User resource $organization/$username(resource: ${useridentity._3}) has Root level access permissions") // Root level permissions are used in test environments, any other user should not have root level access in a production environment.
                                       AuthRoles.SuperUser
                                     case (true, false, _) =>
                                       AuthRoles.HubAdmin
                                     case (false, true, _) =>
                                       AuthRoles.AdminUser
                                     case _ =>
                                       AuthRoles.User
                                   },
                               username = username))
                
                // Guard, should never hit this condition. Reject the authentication attempt if true, as we cannot determine this resource's identity.
                if (authenticatedUserIdentity.get.isUser &&
                    authenticatedUserIdentity.get.identifier.isEmpty) {
                  logger.error(s"The ${authenticatedUserIdentity.get.role} resource ${authenticatedUserIdentity.get.resource} is missing a UUID")
                  
                  None
                }
                else
                  authenticatedUserIdentity
                
              case _ => None
            }
          }
          else
            None
        }
      case _ =>
        Future.successful(None)
    }
  }
  
  //someday: use directive https://doc.pekko.io/docs/pekko-http/current/routing-dsl/directives/misc-directives/selectPreferredLanguage.html to support a different language for each client
  lazy val routes: Route =
    DebuggingDirectives.logRequestResult(requestResponseLogging _) {
      pathPrefix("v1") {
        respondWithDefaultHeaders(`Cache-Control`(Seq(`max-age`(0), `must-revalidate`, `no-cache`, `no-store`)),
                                  // RawHeader("Content-Type", "application/json"/*; charset=UTF-8"*/),
                                  RawHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload"), // 2 years
                                  RawHeader("X-Content-Type-Options", "nosniff"),
                                  RawHeader("X-XSS-Protection", "1; mode=block")) {
          handleExceptions(myExceptionHandler) {
            handleRejections(corsRejectionHandler.withFallback(myRejectionHandler)) {
              cors() {
                handleExceptions(myExceptionHandler) {
                  handleRejections(corsRejectionHandler.withFallback(myRejectionHandler).seal) {
                    // These routes do not require Authentication. They accept all requests.
                    SwaggerDocService.routes ~
                    swaggerUiRoutes ~
                    testRoute ~
                    version ~
                    Route.seal(
                    authenticateBasicAsync(realm = "Exchange", myUserPassAuthenticator) {
                      validIdentity =>
                        agentConfigurationManagement(validIdentity) ~
                        agreement(validIdentity) ~
                        agreementBot(validIdentity) ~
                        agreementBots(validIdentity) ~
                        agreementNode(validIdentity) ~
                        agreements(validIdentity) ~
                        agreementsNode(validIdentity) ~
                        changePassword(validIdentity) ~
                        changes(validIdentity) ~
                        cleanup(validIdentity) ~
                        clearAuthCache(validIdentity) ~
                        confirm(validIdentity) ~
                        confirmAgreement(validIdentity) ~
                        configuration(validIdentity) ~
                        configurationState(validIdentity) ~
                        deploymentPattern(validIdentity) ~
                        deploymentPatternAgreementBot(validIdentity) ~
                        deploymentPatterns(validIdentity) ~
                        deploymentPatternsAgreementBot(validIdentity) ~
                        deploymentPatternsCatalog(validIdentity) ~
                        deploymentPolicies(validIdentity) ~
                        deploymentPoliciesAgreementBot(validIdentity) ~
                        deploymentPolicy(validIdentity) ~
                        deploymentPolicyAgreementBot(validIdentity) ~
                        deploymentPolicySearch(validIdentity) ~
                        details(validIdentity) ~
                        dockerAuth(validIdentity) ~
                        dockerAuths(validIdentity) ~
                        dropDB(validIdentity) ~
                        errors(validIdentity) ~
                        heartbeatAgreementBot(validIdentity) ~
                        heartbeatNode(validIdentity) ~
                        initializeDB(validIdentity) ~
                        keyDeploymentPattern(validIdentity) ~
                        keyService(validIdentity) ~
                        keysDeploymentPattern(validIdentity) ~
                        keysService(validIdentity) ~
                        managementPolicies(validIdentity) ~
                        managementPolicy(validIdentity) ~
                        maxChangeId(validIdentity) ~
                        messageAgreementBot(validIdentity) ~
                        messageNode(validIdentity) ~
                        messagesAgreementBot(validIdentity) ~
                        messagesNode(validIdentity) ~
                        myOrganizations(validIdentity) ~
                        node(validIdentity) ~
                        nodeErrorSearch(validIdentity) ~
                        nodeErrorsSearch(validIdentity) ~
                        nodeHealthDeploymentPattern(validIdentity) ~
                        nodeHealthSearch(validIdentity) ~
                        nodeHighAvailabilityGroup(validIdentity) ~
                        nodes(validIdentity) ~
                        nodeServiceSearch(validIdentity) ~
                        nodeGroup(validIdentity) ~
                        nodeGroups(validIdentity) ~
                        organization(validIdentity) ~
                        organizationDeploymentPatterns(validIdentity) ~
                        organizations(validIdentity) ~
                        // organizationServices(validIdentity) ~
                        organizationStatus(validIdentity) ~
                        policyNode(validIdentity) ~
                        policyService(validIdentity) ~
                        reload(validIdentity) ~
                        searchNode(validIdentity) ~
                        service(validIdentity) ~
                        services(validIdentity) ~
                        servicesCatalog(validIdentity) ~
                        status(validIdentity) ~
                        statusManagementPolicy(validIdentity) ~
                        statusNode(validIdentity) ~
                        // statusOrganization ~
                        statuses(validIdentity) ~
                        token(validIdentity) ~
                        user(validIdentity) ~
                        users(validIdentity)
                    })
                  }
                }
              }
            }
          }
        }
      }
    }
  
  val db: Database = DatabaseConnection.getDatabase//Database.forConfig("exchange-db-connection", system.settings.config.getConfig("exchange-db-connection"))
  def getDb: Database = db

  system.registerOnTermination(() => db.close())

  // Upgrade the db if necessary
  try {
    ExchangeApiTables.upgradeDb(db)
  }
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

  /*implicit val ownershipCache = CacheBuilder.newBuilder()
                                                                        .maximumSize(Configuration.getConfig.getInt("api.cache.resourcesMaxSize"))
                                                                        .expireAfterWrite(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds"), TimeUnit.SECONDS)
                                                                        .build[String, Entry[UUID]]
  implicit val resourceOwnership = GuavaCache(ownershipCache) */
  
  def getOwnerOfResource(organization: String, resource: String, something: String) = {
    val getOwnerOfResource =
      for {
         owner <-
           something match {
             case "agreement_bot" =>
               Compiled(AgbotsTQ.filter(agbots => agbots.id === resource &&
                                                  agbots.orgid === organization)
                                .take(1)
                                .map(_.owner))
                 .result
             case "deployment_pattern" =>
               Compiled(PatternsTQ.filter(deployPatterns => deployPatterns.pattern === resource &&
                                                            deployPatterns.orgid === organization)
                                  .take(1)
                                  .map(_.owner))
                 .result
              case "deployment_policy" =>
                Compiled(BusinessPoliciesTQ.filter(deployPolicies => deployPolicies.businessPolicy === resource &&
                                                                     deployPolicies.orgid === organization)
                                           .take(1)
                                           .map(_.owner))
                .result
             case "management_policy" =>
               Compiled(ManagementPoliciesTQ.filter(managePolicies => managePolicies.managementPolicy === resource &&
                                                                      managePolicies.orgid === organization)
                                            .take(1)
                                            .map(_.owner))
                 .result
             case "node" =>
               Compiled(NodesTQ.filter(nodes => nodes.id === resource &&
                                                nodes.orgid === organization)
                               .take(1)
                               .map(_.owner))
                 .result
             case "service" =>
               Compiled(ServicesTQ.filter(services => services.service === resource &&
                                                      services.orgid === organization)
                                  .take(1)
                                  .map(_.owner))
                 .result
             case _ =>
               DBIO.failed(new Exception())
           }
      } yield owner.headOption
    
    Await.result(db.run(getOwnerOfResource.transactionally), 15.seconds)
  }

  /** Task for trimming `resourcechanges` table */
  def trimResourceChanges(): Unit ={
    // Get the time for trimming rows from the table
    val timeExpires: Timestamp = ApiTime.pastUTCTimestamp(system.settings.config.getInt("api.resourceChanges.ttl"))
    db.run(ResourceChangesTQ.getRowsExpired(timeExpires).delete.asTry).map({
      case Success(v) =>
        if (v <= 0) logger.debug("No resource changes to trim")
        else logger.info("resourcechanges table trimmed, number of records deleted: " + v.toString)
      case Failure(_) => logger.error("ERROR: could not trim resourcechanges table")
    })
  }

  /** Variables and pekko Actor for trimming `resourcechanges` table */
  val Cleanup = "cleanup";
  class ChangesCleanupActor(timerInterval: Int = system.settings.config.getInt("api.resourceChanges.cleanupInterval")) extends Actor with Timers{
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

  /** Variables and pekko Actor for removing expired nodemsgs and agbotmsgs */
  val CleanupExpiredMessages = "cleanupExpiredMessages"
  
  class MsgsCleanupActor(timerInterval: Int = system.settings.config.getInt("api.defaults.msgs.expired_msgs_removal_interval")) extends Actor with Timers {
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
  val secondsToWait: Int = system.settings.config.getInt("api.service.shutdownWaitForRequestsToComplete") // ExchConfig.getpekkoConfig() also makes the pekko unbind phase this long
  
  var serverBindingHttp: Option[Http.ServerBinding] = None
  var serverBindingHttps: Option[Http.ServerBinding] = None
  val serviceHost = system.settings.config.getString("api.service.host")
  
  val truststore: Option[String] =
    try {
      Option(system.settings.config.getString("api.tls.truststore"))
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
                                        system.settings.config.getString("api.tls.password").toCharArray)
      keyManager.init(keyStore, system.settings.config.getString("api.tls.password").toCharArray)
      trustManager.init(keyStore)
      sslContext.init(keyManager.getKeyManagers,
                      trustManager.getTrustManagers,
                      new SecureRandom)
  
      // Start serving client requests
      Http().newServerAt(serviceHost, -1) // pekko.http.server.default-https-port
            .enableHttps(ConnectionContext.httpsServer(() => {               // Custom TLS parameters
              val engine: SSLEngine = sslContext.createSSLEngine()
              
              engine.setEnabledProtocols(Array("TLSv1.3"))
              engine.setEnabledCipherSuites(Array("TLS_AES_256_GCM_SHA384",
                                                  "TLS_CHACHA20_POLY1305_SHA256"))
              engine.setUseClientMode(false)
              engine
            }))
            .bind(routes) //.bind(cors.corsHandler(routes))
            .map(_.addToCoordinatedShutdown(hardTerminationDeadline = secondsToWait.seconds))
            .onComplete {
              case Success(binding) =>
                logger.info("Server online at "+ s"https://${serviceHost}:${binding.localAddress.getPort}/") // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
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
  
  if(system.settings.config.hasPath("pekko.http.server.default-http-port")) {
    Http().newServerAt(serviceHost, -1) // pekko.http.server.default-http-port
          .bind(routes) //.bind(cors.corsHandler(routes))
          .map(_.addToCoordinatedShutdown(hardTerminationDeadline = secondsToWait.seconds))
          .onComplete {
            case Success(binding) =>
              logger.info("Server online at "+ s"http://${serviceHost}:${binding.localAddress.getPort}/") // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
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
