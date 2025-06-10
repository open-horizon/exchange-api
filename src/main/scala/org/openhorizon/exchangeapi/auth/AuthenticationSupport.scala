package org.openhorizon.exchangeapi.auth

import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive, Directive0, Directive1, ValidationRejection}
import org.openhorizon.exchangeapi.auth.Access.Access
import org.openhorizon.exchangeapi.utility.{AuthRejection, Configuration}
import org.openhorizon.exchangeapi.{ExchangeApi, ExchangeApiApp}
import slick.jdbc.PostgresProfile.api._

import java.util
import java.util.Base64
import javax.security.auth.Subject
import javax.security.auth.login.{AppConfigurationEntry, LoginContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util._
import scala.util.matching.Regex

/* Used by all routes classes to Authenticates the client credentials and then checks the ACLs for authorization.
The main authenticate/authorization flow is:
- an api route calls the directive exchAuth() which calls authenticate() which:
  - initiates a login with JAAS, giving it ExchCallbackHandler (which gives it access to the creds)
    - calls the login() methods of each module listed in resources/jaas.config until 1 doesnt throw an exception
      - in IbmCloudModule.login() it calls IbmCloudAuth.authenticateUser()
      - in Module.login() it calls Identity.authenticate()
  - returns an AuthenticatedIdentity (that contains both the exchange-specific Identity, and the JAAS Subject), or it throws an authentication exception
- from the return of authenticate() the route then calls AuthenticatedIdentity.authorizeTo() with the target and access required
  - calls the correct Identity subclass authorizeTo() method
    - if the Identity has the specific access required it returns the identity, otherwise it throws an authorization exception
*/
object AuthenticationSupport {
  def logger: LoggingAdapter = ExchangeApi.defaultLogger
  
  val decodedAuthRegex = new Regex("""^(.+):(.+)\s?$""")
  
  // Split an id in the form org/id and return both parts. If there is no / we assume it is an id without the org.
  /*def compositeIdSplit(compositeId: String): (String, String) = {
    val reg: Regex = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org, id) => (org, id)
      // These 2 lines never get run, and aren't needed. If we really want to handle a special, put something like this as the 1st case above: case reg(org, "") => return (org, "")
      //case reg(org, _) => return (org, "")
      //case reg(_, id) => return ("", id)
      case _ => ("", compositeId)
    }
  }*/

  // Decodes the basic auth and parses it to return Some(Creds) or None if the creds aren't there or aren't parsable
  // Note: this is in the object, not the trait, so we can also use it from ExchangeApiApp for logging of each request
  def parseCreds(encodedAuth: String): Option[Creds] = {
    try {
      val decodedAuthStr = new String(Base64.getDecoder.decode(encodedAuth), "utf-8")
      
      decodedAuthStr match {
        case decodedAuthRegex(compositeId, tok) =>
          // logger.debug("id=" + compositeId + ",tok=" + tok + ".")
          
          val reg: Regex = """^(\S*?)/(\S*)$""".r
          
          compositeId match {
            case reg(organization, username) =>
              (organization, username)
              Option(Creds(id = compositeId, token = tok))
            case _ =>
              ("", compositeId)
              Option(Creds(id = compositeId, token = tok))
          }
        case _ =>
          None
      }
    }
    catch {
      case _: IllegalArgumentException => None // this is the exception from decode()
    }
  }

  /* Used in the LoginContext in authenticate()
    Note: the login config was originally loaded at runtime from src/main/resources/jaas.config with this content:
      ExchangeApiLogin {
       com.horizon.exchangeapi.auth.IbmCloudModule sufficient;
       com.horizon.exchangeapi.auth.Module sufficient;
      };
    But i had trouble getting it loaded from the docker image that the sbt-native-packager builds. So just putting the config in our code for now.
  */
  val loginConfig: javax.security.auth.login.Configuration = new javax.security.auth.login.Configuration {
    override def getAppConfigurationEntry(name: String): Array[AppConfigurationEntry] = {
      Array[AppConfigurationEntry](
        new AppConfigurationEntry("org.openhorizon.exchangeapi.auth.cloud.IbmCloudModule", AppConfigurationEntry.LoginModuleControlFlag.SUFFICIENT, new util.HashMap[String, String]()),
        new AppConfigurationEntry("org.openhorizon.exchangeapi.auth.Module", AppConfigurationEntry.LoginModuleControlFlag.SUFFICIENT, new util.HashMap[String, String]()))
    }
  }

  // Note: not currently ever set to true, because db migrations happen during startup, before we start server requests
  var migratingDb = false // used to lock everyone out during db migration
  def isDbMigration: Boolean = migratingDb
  // def setDbMigration(dbMigration: Boolean): Unit = { migratingDb = dbMigration }
}

trait AuthenticationSupport extends AuthorizationSupport {
  // We could add a before action with before() {}, but sometimes they need to pass in user/pw, and sometimes id/token
  // I tried using code from http://www.scalatra.org/2.4/guides/http/authentication.html, but it throws an exception.

  def db: Database // get access to the db object in ExchangeApiApp
  implicit def logger: LoggingAdapter

  // Custom directive to extract the request body (a.k.a entity) as a string (w/o json unmarshalling)
  //someday: currently this must be used as a separate directive, don't yet know how to combine it with the other directives using &
  // Warning: can't use this when you are also using the normal entity(as[PatchNodesRequest]), or sometimes it will cause
  //    error 'Substream Source cannot be materialized more than once'. So if you use this directive, you'll have to use
  //    parse(request.body).extract[PatchNodesRequest] yourself to unmarshal the request body.
  def extractRawBodyAsStr: Directive1[String] = {
    extractStrictEntity(Duration(Configuration.getConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS)).flatMap { entity =>
      provide(entity.data.utf8String)
    }
  }

  // Custom directive, like validate() from pekko.http.scaladsl.directives.MiscDirectives, except that the checking function returns
  // an Option[String] (instead of a boolean) so if invalid input is found, it can return an error msg specific to the problem.
  def validateWithMsg(check: => Option[String]): Directive0 =
    Directive {
      val errorMsg: Option[String] = check
      inner => if (errorMsg.isEmpty) inner(()) else reject(ValidationRejection(errorMsg.get))
    }

  /* some past attempts at using the pekko authenticateBasic directive, and a custom directive
  def exchangeAuth(credentials: Credentials): Option[AuthenticatedIdentity] = {
    logger.debug(s"exchangeAuth: credentials: $credentials")
    credentials match {
      case pw @ Credentials.Provided(id) =>
        authenticate(Creds("foo", "bar")) match {
          case Failure(_: AuthException) => None
          case Failure(_) => None // just to satisfy the compiler, should never get here
          case Success(authenticatedIdentity) => Some(authenticatedIdentity)
        }
      case _ => None
    }
  }

  def authExch(target: Target, access: Access, hint: String = ""): Directive[(Identity)] = Directive[(Identity)] { inner => ctx =>
    val optEncodedAuth = ctx.request.getHeader("Authorization")
    val encodedAuth = if (optEncodedAuth.isPresent) optEncodedAuth.get().value() else ""
    //val basicAuthRegex = new Regex("^Basic ?(.*)$")
    val optCreds = encodedAuth match {
      case ExchangeApiApp.basicAuthRegex(basicAuthEncoded) =>
        AuthenticationSupport.parseCreds(basicAuthEncoded)
      case _ => None
    }
    authenticate2(optCreds, hint = hint) match {
      case Failure(_) => inner((IIdentity(Creds("invalid","invalid"))))(ctx) //reject(ValidationRejection(t.getMessage))
      case Success(authenticatedIdentity) =>
        authenticatedIdentity.authorizeTo(target, access) match {
          case Failure(_) => inner((IIdentity(Creds("invalid","invalid"))))(ctx) //reject(AuthRejection(t))
          case Success(identity) => inner((identity))(ctx)
        }
    }
  }

  def exchAuth(target: Target, access: Access, hint: String = ""): Directive1[Identity] = {
      val optCreds = Some(Creds("IBM/bp@us.ibm.com", "betheedge"))
      authenticate2(optCreds, hint = hint) match {
        case Failure(t) => reject(AuthRejection(t))
        case Success(authenticatedIdentity) =>
          authenticatedIdentity.authorizeTo(target, access) match {
            case Failure(t) => reject(AuthRejection(t))
            case Success(identity) => provide(identity)
          }
      }
  }

  // Tries to do both authentication and then authorization. If successful, returns Identity. Otherwise returns an AuthException subclass
  def auth(optionalHttpCredentials: Option[HttpCredentials], target: Target, access: Access, hint: String = ""): Try[Identity] = {
    authenticateHttpCredentials(optionalHttpCredentials, hint = hint) match {
      case Failure(t) => Failure(t)
      case Success(authenticatedIdentity) =>
        authenticatedIdentity.authorizeTo(target, access)
    }
  }

  // Used to authenticate and log all the routes, returning an authenticated Identity, which can be used for authorization, or halting the request due to invalid credentials.
  def authenticateHttpCredentials(optionalHttpCredentials: Option[HttpCredentials], hint: String = ""): Try[AuthenticatedIdentity] = {
    val encodedAuth = optionalHttpCredentials.map(_.token()).getOrElse("")
    val creds = AuthenticationSupport.parseCreds(encodedAuth)
    authenticate(creds, hint = hint)
  }
  */
  
  // Custom directive to extract the Authorization header creds and authenticate/authorize to the exchange
  //someday: this must be used as a separate directive, don't yet know how to combine it with the other directives using &
  def exchAuth(target: Target, access: Access, hint: String = "", validIdentity: Identity2): Directive1[Identity] = {
    
             val ident: Identity =
               validIdentity.role match {
                 case AuthRoles.Node =>
                   INode(Creds(id = s"${validIdentity.organization}/${validIdentity.username}", token = ""), validIdentity)
                 case AuthRoles.Agbot =>
                   IAgbot(Creds(id = s"${validIdentity.organization}/${validIdentity.username}", token = ""), validIdentity)
                 case _ =>
                   IUser(Creds(id = s"${validIdentity.organization}/${validIdentity.username}", token = ""), validIdentity)
               }
             
             val loginCtx =
               new LoginContext("ExchangeApiLogin",
                                null,
                                null,
                                AuthenticationSupport.loginConfig).getSubject
                                
             // logger.debug(s"AuthenticationSupport:225:    login.context:  ${loginCtx}")
             
             val authenticatedIdentity =
                AuthenticatedIdentity(ident,
                                      loginCtx)
             
             authenticatedIdentity.authorizeTo(target, access) match {
               case Failure(t) =>
                 // logger.error(s"AuthenticationSupport:233     t:${t.getMessage}")
                 reject(AuthRejection(t))
               case Success(identity) =>
                 // logger.debug(s"AuthenticationSupport:236     authorizeto:success")
                 provide(identity)
             }
  }

  /** Returns a temporary pw reset token. */
  def createToken(username: String): String = {
    ""
  }
}




