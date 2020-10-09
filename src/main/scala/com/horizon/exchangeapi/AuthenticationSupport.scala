package com.horizon.exchangeapi

import akka.event.LoggingAdapter
import akka.http.scaladsl.server.{Directive, Directive0, ValidationRejection}
import akka.http.scaladsl.server.Directive1
import com.horizon.exchangeapi.Access.Access
import javax.security.auth.login.{AppConfigurationEntry, Configuration}

import scala.util.matching.Regex
import akka.http.scaladsl.server.Directives._
import com.horizon.exchangeapi.auth._
import javax.security.auth.Subject
import javax.security.auth.login.LoginContext
import org.mindrot.jbcrypt.BCrypt
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import slick.jdbc.PostgresProfile.api._

import scala.jdk.CollectionConverters.CollectionHasAsScala

import scala.util._
import scala.concurrent.duration._
import java.util.Base64
import java.util

/* Used by all routes classes to Authenticates the client credentials and then checks the ACLs for authorization.
The main authenticate/authorization flow is:
- an api route calls authenticate() which:
  - initiates a login with JAAS, giving it ExchCallbackHandler (which gives it access to the creds)
    - calls the login() methods of each module listed in resources/jaas.config until 1 doesnt throw an exception
      - in IbmCloudModule.login() it calls IbmCloudAuth.authenticateUser()
      - in Module.login() it calls Identity.authenticate()
  - returns an AuthenticatedIdentity (that contains both the exchange-specific Identity, and the JAAS Subject)
- from the return of authenticate() the route then calls AuthenticatedIdentity.authorizeTo() with the target and access required
  - calls the correct Identity subclass authorizeTo() method
    - determines if the Identity has the specific access required
*/
object AuthenticationSupport {
  def logger: LoggingAdapter = ExchangeApi.defaultLogger
  val decodedAuthRegex = new Regex("""^(.+):(.+)\s?$""")

  // Decodes the basic auth and parses it to return Some(Creds) or None if the creds aren't there or aren't parsable
  // Note: this is in the object, not the trait, so we can also use it from ExchangeApiApp for logging of each request
  def parseCreds(encodedAuth: String): Option[Creds] = {
    try {
      val decodedAuthStr = new String(Base64.getDecoder.decode(encodedAuth), "utf-8")
      decodedAuthStr match {
        case decodedAuthRegex(id, tok) => /*logger.debug("id="+id+",tok="+tok+".");*/ Some(Creds(id, tok))
        case _ => None
      }
    } catch {
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
  val loginConfig: Configuration = new Configuration {
    override def getAppConfigurationEntry(name: String): Array[AppConfigurationEntry] = {
      Array[AppConfigurationEntry](
        new AppConfigurationEntry("com.horizon.exchangeapi.auth.IbmCloudModule", AppConfigurationEntry.LoginModuleControlFlag.SUFFICIENT, new util.HashMap[String, String]()),
        new AppConfigurationEntry("com.horizon.exchangeapi.auth.Module", AppConfigurationEntry.LoginModuleControlFlag.SUFFICIENT, new util.HashMap[String, String]()))
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
    extractStrictEntity(Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS)).flatMap { entity =>
      provide(entity.data.utf8String)
    }
  }

  // Custom directive, like validate() from akka.http.scaladsl.directives.MiscDirectives, except that the checking function returns
  // an Option[String] (instead of a boolean) so if invalid input is found, it can return an error msg specific to the problem.
  def validateWithMsg(check: => Option[String]): Directive0 =
    Directive {
      val errorMsg: Option[String] = check
      inner => if (errorMsg.isEmpty) inner(()) else reject(ValidationRejection(errorMsg.get))
    }

  /* some past attempts at using the akka authenticateBasic directive, and a custom directive
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
  def exchAuth(target: Target, access: Access, hint: String = ""): Directive1[Identity] = {
    // val optEncodedAuth = ctx.request.getHeader("Authorization")
    extract(_.request.getHeader("Authorization")).flatMap { optEncodedAuth =>
      val encodedAuth: String = if (optEncodedAuth.isPresent) optEncodedAuth.get().value() else ""
      val optCreds: Option[Creds] = encodedAuth match {
        case ExchangeApiApp.basicAuthRegex(basicAuthEncoded) =>
          AuthenticationSupport.parseCreds(basicAuthEncoded)
        case _ => None
      }
      authenticate(optCreds, hint = hint) match {
        case Failure(t) => reject(AuthRejection(t))
        case Success(authenticatedIdentity) =>
          authenticatedIdentity.authorizeTo(target, access) match {
            case Failure(t) => reject(AuthRejection(t))
            case Success(identity) => provide(identity)
          }
      }
    }
  }

  def authenticate(creds: Option[Creds], hint: String = ""): Try[AuthenticatedIdentity] = {
    /*
     * For JAAS, the LoginContext is what you use to attempt to login a user
     * and get a Subject back. It takes care of creating the LoginModules and
     * calling them. It is configured by the jaas.config file, which specifies
     * which LoginModules to use.
     */
    //logger.debug(s"authenticate: $creds")
    if (creds.isEmpty) return Failure(new InvalidCredentialsException)
    val loginCtx = new LoginContext(
      "ExchangeApiLogin", null,
      new ExchCallbackHandler(RequestInfo(creds.get, /*request, params,*/ AuthenticationSupport.isDbMigration /*, anonymousOk*/ , hint)),
      AuthenticationSupport.loginConfig)
    for (err <- Try(loginCtx.login()).failed) {
      return Failure(err)
    }
    val subject: Subject = loginCtx.getSubject // if we authenticated an api key, the subject contains the associated username
    Success(AuthenticatedIdentity(subject.getPrivateCredentials(classOf[Identity]).asScala.head, subject))
  }

  /** Returns a temporary pw reset token. */
  def createToken(username: String): String = {
    // Get their current pw to use as the secret
    AuthCache.getUser(username) match {
      case Some(userHashedTok) => Token.create(userHashedTok) // always create the token with the hashed pw because that will always be there during creation and validation of the token
      case None => "" // this case will never happen (we always pass in superUser), but here to remove compile warning
    }
  }
}

/** Hash a password or token, and compare a pw/token to its hashed value */
object Password {
  // Using jbcrypt, see https://github.com/jeremyh/jBCrypt and http://javadox.com/org.mindrot/jbcrypt/0.3m/org/mindrot/jbcrypt/BCrypt.html
  val defaultLogRounds = 10 // hashes the pw 2**logRounds times
  val minimumLogRounds = 4 // lowest brcypt will accept

  /**
   * Returns the hashed value of the given password or token. Lowest logRounds allowed is 4.
   * Note: since BCrypt.hashpw() uses a different salt each time, 2 hashes of the same pw will be different. So it is not valid to hash the
   *     clear pw specified by the user and compare it to the already-hashed pw in the db. You must use BCrypt.checkpw() instead.
   */
  def hash(password: String): String = { BCrypt.hashpw(password, BCrypt.gensalt(defaultLogRounds)) }

  def fastHash(password: String): String = { BCrypt.hashpw(password, BCrypt.gensalt(minimumLogRounds)) }

  /** Returns true if plainPw matches hashedPw */
  def check(plainPw: String, hashedPw: String): Boolean = {
    if (hashedPw == "") return false // this covers the case when the root user is disabled
    BCrypt.checkpw(plainPw, hashedPw)
  }

  /** Returns true if this pw/token is already hashed */
  def isHashed(password: String): Boolean = {
    //password.startsWith("""$2a$10$""")
    // bcrypt puts $2a$10$ at the beginning of encrypted values, where the 10 is the logRounds used (it will always be a 2 digit number)
    val regex: Regex = raw"""^\$$2a\$$\d\d\$$""".r
    regex.findFirstIn(password).isDefined
  }

  /** If already hash, return it, otherwise hash it */
  def hashIfNot(password: String): String = if (isHashed(password)) password else hash(password)
}

/** Create and validate web tokens that expire */
object Token {
  // From: https://github.com/pauldijou/jwt-scala
  val defaultExpiration = 600 // seconds
  val algorithm: JwtAlgorithm.HS256.type = JwtAlgorithm.HS256

  /** Returns a temporary pw reset token. */
  def create(secret: String, expiration: Int = defaultExpiration): String = {
    //implicit val clock: Clock = Clock.systemUTC()
    //Jwt.encode(JwtClaim({"""{"user":1}"""}).issuedNow.expiresIn(defaultExpiration), secret, algorithm)
    Jwt.encode(JwtClaim({ """{"user":1}""" }).expiresAt(ApiTime.nowSeconds + expiration), secret, algorithm)
  }

  /** Returns true if the token is correct for this secret and not expired */
  def isValid(token: String, secret: String): Boolean = { Jwt.isValid(token, secret, Seq(algorithm)) }
}
