/**
 * Exchange API main scalatra servlet app.
 *
 *  Used https://github.com/scalatra/scalatra-website-examples/tree/master/2.4/persistence/scalatra-slick as an initial example.
 */

package org.openhorizon.exchangeapi

import com.github.benmanes.caffeine.cache
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import org.openhorizon.exchangeapi.route.administration.{ClearAuthCache, Configuration, DropDatabase, InitializeDatabase, OrganizationStatus, Reload, Status, Version}
import org.openhorizon.exchangeapi.route.agreementbot.{AgreementBot, AgreementBots, DeploymentPattern, DeploymentPatterns, DeploymentPolicies, DeploymentPolicy, Heartbeat}
import org.openhorizon.exchangeapi.table
import org.openhorizon.exchangeapi.table.{ExchangeApiTables, ExchangePostgresProfile}
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigSyntax, ConfigValue}
import jakarta.ws.rs.{DELETE, GET, PUT, Path}
import org.apache.pekko.Done
import org.apache.pekko.actor.{Actor, ActorRef, ActorSystem, CoordinatedShutdown, Props, Timers}
import org.apache.pekko.event.{Logging, LoggingAdapter}
import org.apache.pekko.http.cors.scaladsl.CorsDirectives._
import org.apache.pekko.http.cors.scaladsl.CorsRejection
import org.apache.pekko.http.javadsl.model.HttpHeader
import org.apache.pekko.http.javadsl.server.CustomRejection
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.{HttpMethod, HttpMethods, HttpRequest, HttpResponse, ResponseEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `must-revalidate`, `no-cache`, `no-store`}
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken, RawHeader, `Cache-Control`}
import org.apache.pekko.http.scaladsl.server.{AuthenticationFailedRejection, AuthorizationFailedRejection, CircuitBreakerOpenRejection, ExceptionHandler, ExpectedWebSocketRequestRejection, InvalidOriginRejection, MalformedQueryParamRejection, MalformedRequestContentRejection, MethodRejection, MissingAttributeRejection, MissingCookieRejection, MissingFormFieldRejection, MissingHeaderRejection, MissingQueryParamRejection, Rejection, RejectionHandler, RejectionWithOptionalCause, RequestEntityExpectedRejection, Route, RouteResult, SchemeRejection, TooManyRangesRejection, TransformationRejection, ValidationRejection}
import org.apache.pekko.http.scaladsl.server.RouteResult.Rejected
import org.apache.pekko.http.scaladsl.server.directives.{Credentials, DebuggingDirectives, LogEntry}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.pattern.{BackoffOpts, FutureRef}
import org.json4s.{JValue, _}
import org.openhorizon.exchangeapi.SwaggerDocService.complete
import org.openhorizon.exchangeapi.auth.AuthCache.logger
import org.openhorizon.exchangeapi.auth.{ApiKeyUtils, AuthCache, AuthRoles, AuthenticationSupport, DbConnectionException, IAgbot, INode, IUser, IdNotFoundForAuthorizationException, Identity, Identity2, InvalidCredentialsException, Password}
import org.openhorizon.exchangeapi.route.administration.dropdatabase.Token
import org.openhorizon.exchangeapi.route.agent.AgentConfigurationManagement
import org.openhorizon.exchangeapi.route.agreementbot.agreement.{Agreement, Agreements, Confirm}
import org.openhorizon.exchangeapi.route.agreementbot.message.{Message, Messages}
import org.openhorizon.exchangeapi.route.apikey.UserApiKeys
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
import org.openhorizon.exchangeapi.table.apikey.ApiKeysTQ
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
import scalacache._
import scalacache.caffeine._
import scalacache.modes.scalaFuture._
import com.github.benmanes.caffeine.cache.Caffeine
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.BackoffOpts.onFailure
import org.apache.pekko.routing.NoRouter
import org.apache.pekko.util.ByteString
import org.json4s.jackson.{JsonMethods, Serialization}
import org.springframework.security.crypto.bcrypt.BCrypt
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
  with org.openhorizon.exchangeapi.route.agreementbot.agreement.Confirm
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
  with UserApiKeys
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
  
  //AuthCache.createRootInCache()
  
  // Check common overwritten pekko configuration parameters
  Future { logger.debug("pekko.corrdinated-shutdown:  " + system.settings.config.getConfig("pekko.coordinated-shutdown").toString) }
  Future { logger.debug("pekko.loglevel: " + system.settings.config.getString("pekko.loglevel")) }
  Future { logger.debug("pekko.http.parsing: " + system.settings.config.getConfig("pekko.http.parsing").toString) }
  Future { logger.debug("pekko.http.server: " + system.settings.config.getConfig("pekko.http.server").toString) }
  
  // Set a custom exception handler. See https://doc.pekko.io/docs/pekko-http/current/routing-dsl/exception-handling.html#exception-handling
  implicit def myExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case exception: UnsupportedOperationException =>
        complete(StatusCodes.Unauthorized, ApiResponse(ApiRespType.BADCREDS, "unauthorized"))
      case exception: MappingException =>
        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, exception.getMessage)))
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
                      case rejection: AuthenticationFailedRejection =>
                        complete{ (StatusCodes.Unauthorized, ApiResponse(ApiRespType.BADCREDS, "invalid.credentials")) }
                    }
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
                    .handle {
                      case MissingQueryParamRejection(parameterName) =>
                        complete((StatusCodes.BadRequest, ApiResponse(ApiRespType.BAD_INPUT, parameterName)))
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
      Option(LogEntry(s"${req.uri.authority.host.address}:$authId ${req.method.name} ${req.uri}: ${res.status}", Logging.InfoLevel))
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

 
  final case class testResp(result: String = "OK")
  
  def testRoute = {
    path("test") {
      get {
        complete(testResp())
      }
    }
  }
  
  val identityCacheSettings: cache.Cache[String, Entry[(Identity2, String)]] =
    Caffeine.newBuilder()
                .maximumSize(Configuration.getConfig.getInt("api.cache.idsMaxSize"))
                .expireAfterWrite(Configuration.getConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
                .build[String, Entry[(Identity2, String)]]
  implicit val cacheResourceIdentity: CaffeineCache[(Identity2, String)] = CaffeineCache(identityCacheSettings)
  
  // This should only be used for Authorization: Basic challenges using organization/username:password
  // This does not support other authentication types (OAuth, Api Keys), nor using a UUID as provided user.
  // Without unified identity, we have to protect against privilege escalation attacks by defaulting to the user archetype of least permissions.
  /*
   * Had a really elegant solution for grabbing/creating everything needed, doing a union between the table types,
   * and then yielding the identity object and the credential in a tuple. There seems to be a technical limitation
   * present in Slick which frustratingly prevents you from doing a union with an Option[UUID] present in the query.
   * We concatenate the yielded Sequences to together as the next best thing. Certainly not as performant, but it works.
   */
  def getResourceIdentityAndPassword(organization: String,
                                     username: String): Future[((Boolean, Option[UUID], Boolean, Option[UUID], Int), String)] = {
    val getIdentityQuery =
      for {
        nodeIdentities <-
          NodesTQ.filter(nodes => nodes.orgid === organization &&
                                            nodes.id === (organization ++ "/" ++ username))
            .map(nodes => ((false, Rep.None[UUID], false, nodes.owner.?, 0), nodes.token))
            .take(1)
            .result
            
        agbotIdentities <-
          AgbotsTQ.filter(agbots => agbots.orgid === organization && agbots.id === (organization ++ "/" ++ username))
             .map(agbots => ((false, Rep.None[UUID], false, agbots.owner.?, 1), agbots.token))
            .take(1)
            .result
        
        useridentities <-
          UsersTQ.filter(users => users.organization === organization && users.username === username)
             .map(users => ((users.isHubAdmin, users.user.?, users.isOrgAdmin, Rep.None[UUID], 2), users.password.getOrElse("")))
            .take(1)
            .result
      } yield ((nodeIdentities ++ agbotIdentities ++ useridentities)).minBy(_._1._5)
    
    db.run(getIdentityQuery.transactionally)
  }
  
  val reg: Regex = """^(\S*?)/(\S*)$""".r
  
  def getIdentityCacheValueFromDB(organization: String, username: String): Future[(Identity2, String)] = {
    getResourceIdentityAndPassword(organization, username).map {
      // Yield this Resource's identity metadata and construct a future identity object. Resource's stored credential tags along.
      // This object will be returned and used if authenticated.
      identityMetadata: ((Boolean, Option[UUID], Boolean, Option[UUID], Int), String) => (new Identity2(organization, identityMetadata._1, username), identityMetadata._2)
    }
  }
  
  def basicAuthenticator(credentials: Credentials): Future[Option[Identity2]] = {
    credentials match {
      case p@Credentials.Provided(resource) =>
        Future {
          val (organization: String,
               username: String) = {
            resource match {
              case reg(organization: String, username) =>
                (organization, username)
              case _ =>
                ("", "")
            }
          }
          
          if (organization.isEmpty &&
              username.isEmpty) {
            Future.successful(None)
            
          }
          //else if (username == "iamtoken" || username == "token")
            //oauthAuthenticator(organization)(Credentials.apply())
          else if (username == "apikey") {
            val getApiKeysByOrg = Compiled((org: Rep[String]) =>
              ApiKeysTQ.filter(_.orgid === org)
            )
            val getUsernameByUserId = Compiled((org: Rep[String], uid: Rep[UUID]) =>
              UsersTQ.filter(u => u.organization === org && u.user === uid)
                    .map(_.username)
                    .take(1)
            )
            val verifiedIdentityFut = for {
              apiKeys <- db.run(getApiKeysByOrg(organization).result)
              matchedKeyOpt = apiKeys.find(apiKey =>
                p.provideVerify("unused", (_, providedToken) =>
                  Password.check(providedToken, apiKey.hashedKey)
                )
              )
              actualUsernameOpt <- matchedKeyOpt match {
                case Some(matchedKey) =>
                  db.run(getUsernameByUserId(organization, matchedKey.user).result.headOption)
                case None =>
                  Future.successful(None)
              }
              result <- actualUsernameOpt match {
                case Some(actualUsername) =>
                  getResourceIdentityAndPassword(organization, actualUsername).map {
                    case (identityMetadata, _) => Some(new Identity2(organization, identityMetadata, actualUsername))
                  }
                case None =>
                  Future.successful(None)
              }
            } yield result
            verifiedIdentityFut
          }
          else {
            def identityCacheGet (resource: String): Future[(Identity2, String)] = {
              cacheResourceIdentity.cachingF(resource)(ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds)) {
                // On cache miss execute this block to try and find what the value should be.
                // If found add to the cache for next time.
                getIdentityCacheValueFromDB(organization: String, username: String)
              }
            }
            
            def AuthenticationCheck (resourceIdentityAndCred: Option[(Identity2, String)]): Future[Option[Identity2]] = Future {
              resourceIdentityAndCred match {
              case Some(resourceIdentityAndCred) =>
                // Guard, should never hit this condition. Reject the authentication attempt if true, as we cannot determine this resource's identity.
                if (resourceIdentityAndCred._1.role == AuthRoles.Anonymous) {
                  Future { logger.debug(s"Authentication: This resource ${resourceIdentityAndCred._1.resource} yields user archetype ${resourceIdentityAndCred._1.role}.") }
                  None
                }
                // Guard, should never hit this condition. Reject the authentication attempt if true, as we cannot determine this resource's identity.
                else if (resourceIdentityAndCred._1.isUser &&
                        resourceIdentityAndCred._1.identifier.isEmpty) {
                  Future { logger.debug(s"Authentication: The ${resourceIdentityAndCred._1.role} resource ${resourceIdentityAndCred._1.resource} is missing a UUID.") }
                  None
                }
                else if (p.provideVerify(secret = resourceIdentityAndCred._2,
                                          verifier =
                                            // (Hashed credential in database, Plain-text password from Authorization: Basic header)
                                          (storedSecret, requestPassword) => {
                                            if (!storedSecret.startsWith("$argon2id") && BCrypt.checkpw(requestPassword, storedSecret)) {
                                              Future { logger.debug(s"Resource ${resourceIdentityAndCred._1.resource}(${resourceIdentityAndCred._1.identifier.getOrElse("None")}):${resourceIdentityAndCred._1.role} has a credential that is using a legacy BCrypt algorithm. Rehashing this credential to Argon2id.") }
                                              val rehashedCredential = Password.hash(requestPassword)
                                              
                                              Future { cacheResourceIdentity.put(p.identifier)(value = (resourceIdentityAndCred._1, rehashedCredential), ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds)) }
                                              
                                              Future {
                                                resourceIdentityAndCred._1.role match {
                                                  case AuthRoles.Agbot =>
                                                    Future { db.run(AgbotsTQ.filter(_.id === resourceIdentityAndCred._1.resource).map(_.token).update(rehashedCredential))}
                                                  case AuthRoles.Node =>
                                                    Future { db.run(NodesTQ.filter(_.id === resourceIdentityAndCred._1.resource).map(_.token).update(rehashedCredential))}
                                                  case _ =>
                                                    Future { db.run(UsersTQ.filter(_.user === resourceIdentityAndCred._1.identifier.get).map(_.password).update(Option(rehashedCredential)))}
                                                }
                                              }
                                              
                                              Password.check(requestPassword, rehashedCredential)
                                            }
                                            else
                                              /*logger.debug("Line 333:    credential: " + requestPassword + "    secret: " + storedSecret);*/
                                              Password.check(requestPassword, storedSecret)
                                          })) {
                  // Root level permissions are used in test environments, any other user should not have root level access in a production environment.
                  if (resourceIdentityAndCred._1.role == AuthRoles.SuperUser &&
                      resourceIdentityAndCred._1.resource != "root/root")
                    Future { logger.warning(s"User resource ${resourceIdentityAndCred._1.resource}(resource: ${resourceIdentityAndCred._1.identifier.getOrElse("")}) has Root level access permissions") }
                  Option(resourceIdentityAndCred._1) // Return the successfully authenticated Identity
                }
                else
                  None
                case None => None
              }
            }
            
            // Chains the input/output of our futures.
            // Also yields a result this future can use due to nesting futures.
            for {
              fetchedIdentity <- identityCacheGet(resource)
              //_ = logger.debug(s"Async returns: ${a._1.toString}, ${a._1.identifier.getOrElse("None")}")
              authenticatedIdentity <- AuthenticationCheck(Option(fetchedIdentity)) fallbackTo(Future{None})
              authenticatedIdentityRetry <-
                // On failure of the first attempt clear the cache value for this caller's key and run the two functions again. This will force a database pull.
                if (authenticatedIdentity.isEmpty) {
                  cacheResourceIdentity.remove(resource)
                  identityCacheGet(resource).flatMap( fetchedIdentity => AuthenticationCheck(Option(fetchedIdentity)) fallbackTo(Future{None}))
                }
                else
                  Future { None }
              authenticatedIdentityWithCacheInvalidation <-
                Future {
                  if (authenticatedIdentity.isDefined)
                    authenticatedIdentity
                  else
                    authenticatedIdentityRetry
                }
            } yield authenticatedIdentityWithCacheInvalidation
          }
        }.flatMap(x => x) // Flattens the nested futures.
      case _ =>
        Future.successful(None)
    }
  }
  
  def authenticatedRoutes(authenticatedIdentity: Identity2): Route = {
    agentConfigurationManagement(authenticatedIdentity) ~
    agreement(authenticatedIdentity) ~
    agreementBot(authenticatedIdentity) ~
    agreementBots(authenticatedIdentity) ~
    agreementNode(authenticatedIdentity) ~
    agreements(authenticatedIdentity) ~
    agreementsNode(authenticatedIdentity) ~
    changePassword(authenticatedIdentity) ~
    changes(authenticatedIdentity) ~
    cleanup(authenticatedIdentity) ~
    clearAuthCache(authenticatedIdentity) ~
    confirm(authenticatedIdentity) ~
    confirmAgreement(authenticatedIdentity) ~
    configuration(authenticatedIdentity) ~
    configurationState(authenticatedIdentity) ~
    deploymentPattern(authenticatedIdentity) ~
    deploymentPatternAgreementBot(authenticatedIdentity) ~
    deploymentPatterns(authenticatedIdentity) ~
    deploymentPatternsAgreementBot(authenticatedIdentity) ~
    deploymentPatternsCatalog(authenticatedIdentity) ~
    deploymentPolicies(authenticatedIdentity) ~
    deploymentPoliciesAgreementBot(authenticatedIdentity) ~
    deploymentPolicy(authenticatedIdentity) ~
    deploymentPolicyAgreementBot(authenticatedIdentity) ~
    deploymentPolicySearch(authenticatedIdentity) ~
    details(authenticatedIdentity) ~
    dockerAuth(authenticatedIdentity) ~
    dockerAuths(authenticatedIdentity) ~
    dropDB(authenticatedIdentity) ~
    errors(authenticatedIdentity) ~
    heartbeatAgreementBot(authenticatedIdentity) ~
    heartbeatNode(authenticatedIdentity) ~
    initializeDB(authenticatedIdentity) ~
    keyDeploymentPattern(authenticatedIdentity) ~
    keyService(authenticatedIdentity) ~
    keysDeploymentPattern(authenticatedIdentity) ~
    keysService(authenticatedIdentity) ~
    managementPolicies(authenticatedIdentity) ~
    managementPolicy(authenticatedIdentity) ~
    maxChangeId(authenticatedIdentity) ~
    messageAgreementBot(authenticatedIdentity) ~
    messageNode(authenticatedIdentity) ~
    messagesAgreementBot(authenticatedIdentity) ~
    messagesNode(authenticatedIdentity) ~
    myOrganizations(authenticatedIdentity) ~
    node(authenticatedIdentity) ~
    nodeErrorSearch(authenticatedIdentity) ~
    nodeErrorsSearch(authenticatedIdentity) ~
    nodeHealthDeploymentPattern(authenticatedIdentity) ~
    nodeHealthSearch(authenticatedIdentity) ~
    nodeHighAvailabilityGroup(authenticatedIdentity) ~
    nodes(authenticatedIdentity) ~
    nodeServiceSearch(authenticatedIdentity) ~
    nodeGroup(authenticatedIdentity) ~
    nodeGroups(authenticatedIdentity) ~
    organization(authenticatedIdentity) ~
    //organizationDeploymentPatterns(authenticatedIdentity) ~
    organizations(authenticatedIdentity) ~
    // organizationServices(authenticatedIdentity) ~
    organizationStatus(authenticatedIdentity) ~
    policyNode(authenticatedIdentity) ~
    policyService(authenticatedIdentity) ~
    reload(authenticatedIdentity) ~
    searchNode(authenticatedIdentity) ~
    service(authenticatedIdentity) ~
    services(authenticatedIdentity) ~
    servicesCatalog(authenticatedIdentity) ~
    status(authenticatedIdentity) ~
    statusManagementPolicy(authenticatedIdentity) ~
    statusNode(authenticatedIdentity) ~
    // statusOrganization ~
    statuses(authenticatedIdentity) ~
    token(authenticatedIdentity) ~
    users(authenticatedIdentity) ~
    user(authenticatedIdentity) ~
    userApiKeys(authenticatedIdentity)
  }
  
  def oauthAuthenticator(tokenOrganization: String)(credentials: Credentials): Future[Option[Identity2]] = {
    credentials match {
      case bearerCredential@Credentials.Provided(token) =>
        Future {
          val uri = Configuration.getConfig.getString("api.authentication.oauth.provider.user_info.url")
          Future { logger.debug(s"$uri - OAuth: Received bearer token: `$token`") }
          
          def evaluateResponseEntity(method: HttpMethod, entity: ResponseEntity): Future[Option[Identity2]] = {
            entity.dataBytes.runFold(ByteString(""))(_ ++ _).flatMap {
              body =>
                Future { logger.debug(s"$method $uri - byte string: ${body.utf8String}") }
                Unmarshal(body.utf8String).to[String].map {
                  jsonstring =>
                    val responseBody = JsonMethods.parse(jsonstring)
                    Future { logger.debug(s"$method $uri - body:  $responseBody") }
                    
                    if (!(try { (responseBody \ "active").extract[Boolean]} catch { case _: Throwable => true })) {
                      Future { logger.debug(s"$method $uri - OAuth: This access token is not active") }
                      Future { None }
                    }
                    else {
                      uri match {
                        case ibmCloud if ibmCloud.contains("iam.cloud.ibm.com") =>
                          getUserIdentityIBMCloud(parseUserInfoIBMCloud(responseBody))
                            .recoverWith {
                              case exception: ClassNotFoundException =>
                                Future { logger.debug(s"$method $uri - OAuth: No valid organization with tag was found for user.") }
                                Future { None }
                              case exception: ArrayIndexOutOfBoundsException =>
                                Future { logger.debug(s"$method $uri - OAuth: Multiple organizations with the same tag value were found.") }
                                Future { None }
                              case _ =>
                                Future { logger.debug(s"$method $uri - OAuth: Unknown error.") }
                                Future { None }
                            }
                        case _ =>
                          val userInfo = parseUserInfoGeneric(responseBody)
                          
                          if (userInfo.isEmpty)
                            Future { None }
                          else
                            getUserIdentityGeneric(userInfo.get)
                              .recoverWith {
                                case exception: ClassNotFoundException =>
                                  Future { logger.debug(s"$method $uri - OAuth: No valid organization with tag was found for user.") }
                                  Future { None }
                                case exception: ArrayIndexOutOfBoundsException =>
                                  Future { logger.debug(s"$method $uri - OAuth: Multiple organizations with the same tag value were found.") }
                                  Future { None }
                                case exception: Throwable =>
                                  Future { logger.debug(s"$method $uri - OAuth: Unknown error. ${exception.getMessage}") }
                                  Future { None }
                              }
                      }
                    }
                }.flatMap(x => x)
            }
          }
          
          def getUserIdentityGeneric(userMetadata: (String, List[String], String, String)): Future[Option[Identity2]] = {
            import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
            
            val timestamp: Timestamp = ApiTime.nowUTCTimestamp
            
            val getIdentity: DBIOAction[Option[Identity2], NoStream, Effect.Read with Effect with Effect.Write] =
              for {
                organizationAccountMap <-
                  Compiled(OrgsTQ.filter(organizations => organizations.orgid =!= "root")
                                 .filter(organizations => organizations.orgid === tokenOrganization)
                                 .filter(organizations => organizations.tags.+>>("group") inSet userMetadata._2)
                                 .map(_.orgid))
                    .result
                
                _ = Future { logger.debug(s"$uri - Generic OAuth account organization map - organizationAccountMap: ${organizationAccountMap.toString()}") }
                
                _ <-
                  if (organizationAccountMap.size < 1)
                    DBIO.failed(new ClassNotFoundException())
                  else if (1 < organizationAccountMap.size)
                    DBIO.failed(new ArrayIndexOutOfBoundsException())
                  else
                    DBIO.successful(())
                
                user <-
                  Compiled(UsersTQ.filter(users => users.organization === organizationAccountMap.head && users.username === userMetadata._4)
                                  .take(1)
                                  .map(users =>
                                    (users.isHubAdmin,
                                     users.isOrgAdmin,
                                     users.organization,
                                     users.user,
                                     users.username)))
                    .result
                
                userIdentifier <-
                  if (user.isEmpty)
                    (UsersTQ returning UsersTQ.map(_.user)) +=
                      UserRow(createdAt = timestamp,
                              email = Option(userMetadata._1),
                              identityProvider = userMetadata._3,
                              modifiedAt = timestamp,
                              modified_by = None,
                              organization = organizationAccountMap.head,
                              password = None,
                              user = UUID.randomUUID(),
                              username = userMetadata._4)
                  else
                    DBIO.successful(user.head._4)
                
                validIdentity =
                  if (user.isEmpty) {
                    Future { logger.debug(s"$uri - OAuth: Yielding valid identity from user creation.") }
                    Option(Identity2(identifier = Option(userIdentifier), organization = organizationAccountMap.head, owner = None, role = AuthRoles.User, username = userMetadata._4))
                  }
                  else {
                    Future { logger.debug(s"$uri - OAuth: Yielding valid identity from an existing user.") }
                    Option(new Identity2(user.head))
                  }
              } yield validIdentity
            
            db.run(getIdentity.transactionally)
          }
          
          def getUserIdentityIBMCloud(userMetadata: (String, String, String, String, String)): Future[Option[Identity2]] = {
            import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
            
            val timestamp: Timestamp = ApiTime.nowUTCTimestamp
            
            val getIdentity: DBIOAction[Option[Identity2], NoStream, Effect.Read with Effect with Effect.Write] =
              for {
                organizationAccountMap <-
                  Compiled(OrgsTQ.filter(organizations => organizations.orgid =!= "root")
                                 .filter(organizations => organizations.orgid === tokenOrganization)
                                 .filter(organizations => organizations.tags.+>>("ibmcloud_id") like userMetadata._1)
                                 .map(_.orgid))
                    .result
                
                _ = Future { logger.debug (s"\"${userMetadata._1}\"") }
                
                _ = Future { logger.debug(s"$uri - IBM Cloud OAuth account organization map - organizationAccountMap: ${organizationAccountMap.toString()}") }
                
                _ <-
                  if (organizationAccountMap.size < 1)
                    DBIO.failed(new ClassNotFoundException())
                  else if (1 < organizationAccountMap.size)
                    DBIO.failed(new ArrayIndexOutOfBoundsException())
                  else
                    DBIO.successful(())
                
                user <-
                  Compiled(UsersTQ.filter(users => users.organization === organizationAccountMap.head && users.username === userMetadata._5)
                                  .take(1)
                                  .map(users =>
                                    (users.isHubAdmin,
                                     users.isOrgAdmin,
                                     users.organization,
                                     users.user,
                                     users.username)))
                    .result
                
                newUUID = UUID.randomUUID()
                
                userIdentifier <-
                  if (user.isEmpty)
                    (UsersTQ returning UsersTQ.map(_.user)) +=
                      UserRow(createdAt = timestamp,
                              email = Option(userMetadata._2),
                              identityProvider = userMetadata._4,
                              modifiedAt = timestamp,
                              modified_by = None,
                              organization = organizationAccountMap.head,
                              password = None,
                              user = newUUID,
                              username = userMetadata._5)
                  else
                    DBIO.successful(null)
                
                validIdentity =
                  if (user.isEmpty) {
                    Future { logger.debug(s"$uri - OAuth: Yielding valid identity from user creation.") }
                    Option(Identity2(identifier = Option(userIdentifier), organization = organizationAccountMap.head, owner = None, role = AuthRoles.User, username = userMetadata._5))
                  }
                  else {
                    Future { logger.debug(s"$uri - OAuth: Yielding valid identity from an existing user.") }
                    Option(new Identity2(user.head))
                  }
              } yield validIdentity
            
            db.run(getIdentity.transactionally)
          }
          
          def parseUserInfoGeneric(responseBody: JValue): Option[(String, List[String], String, String)] = {
            implicit val defaultFormats: Formats = DefaultFormats
            val groupsClaim = Configuration.getConfig.getString("api.authentication.oauth.provider.user_info.groups_claim_key")
            Future { logger.debug(s"$uri - generic user info parser - groups claim key: $groupsClaim") }
            
            val userMetaData = {
              try {
                Option((responseBody \ "email").extract[String],
                       (responseBody \ groupsClaim).extract[List[String]],
                       try {
                         (responseBody \ "iss").extract[String]
                       }
                       catch {
                         case _:Throwable => uri
                       },
                       (responseBody \ "sub").extract[String])
              }
              catch {
                case _: Throwable =>
                  None
              }
            }
            
            Future { logger.debug(s"$uri - Parsed user info size: ${userMetaData.size}")}
            Future { logger.debug(s"$uri - Parsed user info: (email: ${userMetaData.getOrElse(("None", "None", "None", "None"))._1}, groups: ${userMetaData.getOrElse(("None", "None", "None", "None"))._2}, iss: ${userMetaData.getOrElse(("None", "None", "None", "None"))._3}, sub: ${userMetaData.getOrElse(("None", "None", "None", "None"))._4})") }
            
            userMetaData
          }
          
          // https://iam.cloud.ibm.com/identity/userinfo
          def parseUserInfoIBMCloud(responseBody: JValue): (String, String, String, String, String) = {
            val userMetaData =
              for {
              JObject(userInfo) <- responseBody
              JField("account", JObject(account)) <- userInfo
              JField("bss", JString(bss)) <- account
              JField("email", JString(email)) <- userInfo
              JField("iam_id", JString(iam_id)) <- userInfo
              JField(key, JString(iss)) <- userInfo if key == "xyz"
              JField("sub", JString(bus)) <- userInfo
            } yield (bss, email, iam_id, Option(iss).getOrElse("something something darkside"), bus)
            
            Future { logger.debug(s"$uri - Parsed user info: (account.bss: ${userMetaData.head._1}, email: ${userMetaData.head._2}, iam_id: ${userMetaData.head._3}, iss: ${userMetaData.head._4}, sub: ${userMetaData.head._5})") }
            
            userMetaData.head
          }
          
          def queryUserInfo(method: HttpMethod = HttpMethods.GET, token: String, uri: String): Future[HttpResponse] = {
            Http().singleRequest(HttpRequest(method = method,
                                             uri = uri,
                                             headers = List(Authorization(OAuth2BearerToken(token)))))
          }
          
          for {
            responseGet <- queryUserInfo(token = token, uri = uri)
            responsePost <- queryUserInfo(method = HttpMethods.POST, token = token, uri = uri)
            _ <- Future { logger.debug(s"GET $uri - response:  $responseGet") }
            _ <- Future { logger.debug(s"POST $uri - response:  $responsePost") }
            authenticatedIdentity <-
              responseGet match {
                      case HttpResponse(StatusCodes.OK, _, entity, _) =>
                        evaluateResponseEntity(HttpMethods.GET, entity)
                      case resp@HttpResponse(code, _, _, _) =>
                        Future {
                          logger.debug(s"GET $uri - OAuth: Provider request failed, falling back to POST: $code")
                        }
                        responsePost match {
                          case HttpResponse(StatusCodes.OK, _, entity, _) =>
                            evaluateResponseEntity(HttpMethods.POST, entity)
                          case resp@HttpResponse(code, _, _, _) =>
                            Future {
                              logger.debug(s"POST $uri - OAuth: Provider request failed, all methods exhausted: $code")
                            }
                            Future {
                              None
                            }
                        }
                      case _ => Future {
                        None
                      }
                    }
          } yield authenticatedIdentity
        }.flatMap(x => x)
      case _ => Future { None }
    }
  }
  
  //someday: use directive https://doc.pekko.io/docs/pekko-http/current/routing-dsl/directives/misc-directives/selectPreferredLanguage.html to support a different language for each client
  lazy val routes: Route =
    DebuggingDirectives.logRequestResult(requestResponseLogging _) {
      withLog(logger) {
        pathPrefix("v1") {
          respondWithDefaultHeaders(`Cache-Control`(Seq(`max-age`(0), `must-revalidate`, `no-cache`, `no-store`)),
                                    // RawHeader("Content-Type", "application/json"/*; charset=UTF-8"*/),
                                    RawHeader("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload"), // 2 years
                                    RawHeader("X-Content-Type-Options", "nosniff"),
                                    RawHeader("X-XSS-Protection", "1; mode=block")) {
            handleExceptions(myExceptionHandler) {
              handleRejections(corsRejectionHandler.withFallback(myRejectionHandler).seal) {
                cors() {
                  handleExceptions(myExceptionHandler) {
                    handleRejections((corsRejectionHandler.withFallback(myRejectionHandler)).seal) {
                      // These routes do not require Authentication. They accept all requests.
                      SwaggerDocService.routes ~
                      swaggerUiRoutes ~
                      testRoute ~
                      version ~
                      Route.seal(
                        optionalHeaderValueByName(Configuration.getConfig.getString("api.authentication.oauth.identity.organization.header")) {
                          oauthOrganization =>
                            extractCredentials {
                              creds =>
                                
                                
                                if (Configuration.getConfig.hasPath("api.authentication.oauth.provider.user_info.url") &&
                                    creds.isDefined &&
                                    creds.get.scheme().toLowerCase == "bearer" &&
                                    oauthOrganization.isDefined)
                                  authenticateOAuth2Async(realm = "Exchange", authenticator = oauthAuthenticator(oauthOrganization.get)) {
                                    validIdentity =>
                                      authenticatedRoutes(validIdentity)
                                  }
                                else
                                  authenticateBasicAsync(realm = "Exchange", authenticator = basicAuthenticator) {
                                    validIdentity =>
                                      authenticatedRoutes(validIdentity)
                                  }
                            }
                        }
                      )
                    }
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
  //AuthCache.initAllCaches(db, includingIbmAuth = true)

  implicit val ownershipCache: cache.Cache[String, Entry[(UUID, Boolean)]] =
    Caffeine.newBuilder()
            .maximumSize(Configuration.getConfig.getInt("api.cache.resourcesMaxSize"))
            .expireAfterWrite(Configuration.getConfig.getInt("api.cache.resourcesTtlSeconds"), TimeUnit.SECONDS)
            .build[String, Entry[(UUID, Boolean)]]
    
  implicit val cacheResourceOwnership: CaffeineCache[(UUID, Boolean)] = CaffeineCache(ownershipCache)
  
  def getOwnerOfResource(organization: String, resource: String, resource_type: String): Future[(UUID, Boolean)] = {
    val getOwnerOfResource: DBIOAction[(UUID, Boolean), NoStream, Effect.Read] =
      for {
        owner: Seq[(UUID, Boolean)] <-
           resource_type match {
             case "agreement_bot" =>
               Compiled(AgbotsTQ.filter(agbots => agbots.id === resource &&
                                                  agbots.orgid === organization)
                                .take(1)
                                .map(agbots => (agbots.owner, false)))
                 .result
             case "deployment_pattern" =>
               Compiled(PatternsTQ.filter(deployPatterns => deployPatterns.pattern === resource &&
                                                            deployPatterns.orgid === organization)
                                  .take(1)
                                  .map(deployment_patterns => (deployment_patterns.owner, deployment_patterns.public)))
                 .result
              case "deployment_policy" =>
                Compiled(BusinessPoliciesTQ.filter(deployPolicies => deployPolicies.businessPolicy === resource &&
                                                                     deployPolicies.orgid === organization)
                                           .take(1)
                                           .map(deployPolicies => (deployPolicies.owner, false)))
                .result
             case "management_policy" =>
               Compiled(ManagementPoliciesTQ.filter(managePolicies => managePolicies.managementPolicy === resource &&
                                                                      managePolicies.orgid === organization)
                                            .take(1)
                                            .map(managePolicies => (managePolicies.owner, false)))
                 .result
             case "node" =>
               Compiled(NodesTQ.filter(nodes => nodes.id === resource &&
                                                nodes.orgid === organization)
                               .take(1)
                               .map(nodes => (nodes.owner, false)))
                 .result
             case "service" =>
               Compiled(ServicesTQ.filter(services => services.service === resource &&
                                                      services.orgid === organization)
                                  .take(1)
                                  .map(services => (services.owner, services.public)))
                 .result
             case "user" =>
               Compiled(UsersTQ.filter(users => users.organization === organization &&
                                                users.username === resource.split("/")(1))
                               .take(1)
                               .map(users => (users.user, false)))
                 .result
             case _ =>
               DBIO.failed(new Exception())
           }
      } yield owner.head
    
    db.run(getOwnerOfResource.transactionally)
  }
  
  /*val defaultCachingSettings = CachingSettings(system)
  val lfuCacheSettings =
    defaultCachingSettings.lfuCacheSettings
      .withInitialCapacity(25)
      .withMaxCapacity(50)
      .withTimeToLive(20.seconds)
      .withTimeToIdle(10.seconds)
  val cachingSettings =
    defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings)
  implicit def lfuCache: PekkoCache[(String, String, String), UUID] = LfuCache(cachingSettings))*/
  

  /** Task for trimming `resourcechanges` table */
  def trimResourceChanges(): Unit ={
    // Get the time for trimming rows from the table
    val timeExpires: Timestamp = ApiTime.pastUTCTimestamp(system.settings.config.getInt("api.resourceChanges.ttl"))
    db.run(Compiled(ResourceChangesTQ.getRowsExpired(timeExpires)).delete.asTry).map({
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
    Future {
      db.run(Compiled(NodeMsgsTQ.getMsgsExpired).delete.transactionally.withTransactionIsolation(Serializable).asTry).map {
        case Success(v) => logger.debug("nodemsgs delete expired result: " + v.toString)
        case Failure(_) => logger.error("ERROR: could not remove expired node msgs")
      }
    }
    Future {
      db.run(Compiled(AgbotMsgsTQ.getMsgsExpired).delete.transactionally.withTransactionIsolation(Serializable).asTry).map {
        case Success(v) => logger.debug("agbotmsgs delete expired result: " + v.toString)
        case Failure(_) => logger.error("ERROR: could not remove expired agbot msgs")
      }
    }
  }

  /** Variables and pekko Actor for removing expired nodemsgs and agbotmsgs */
  val CleanupExpiredMessages = "cleanupExpiredMessages"
  
  class MsgsCleanupActor(timerInterval: Int = system.settings.config.getInt("api.defaults.msgs.expired_msgs_removal_interval")) extends Actor with Timers {
    override def preStart(): Unit = {
      timers.startSingleTimer(key = "removeExpiredMsgsOnStart", msg = CleanupExpiredMessages, timeout = 0.seconds)
      timers.startTimerAtFixedRate(interval = timerInterval.seconds, key = "removeExpiredMsgs", msg = CleanupExpiredMessages)
      Future { logger.info("Scheduling Agreement Bot message and Node message cleanup every " + timerInterval.seconds + " seconds") }
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
              Future { logger.info("Server online at " + s"http://${serviceHost}:${binding.localAddress.getPort}/") } // This will schedule to send the Cleanup-message and the CleanupExpiredMessages-message
              serverBindingHttp = Option(binding)
            case Failure(e) =>
              Future { logger.error("HTTP server could not start!") }
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
