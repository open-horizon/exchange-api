package com.horizon.exchangeapi

//import java.time.Clock

import com.horizon.exchangeapi.auth.{AuthErrors, ExchCallbackHandler}
import javax.security.auth.login.LoginContext
import org.mindrot.jbcrypt.BCrypt
//import org.scalatra.servlet.ServletApiImplicits
import org.scalatra.ScalatraBase
import org.slf4j.Logger
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import slick.jdbc.PostgresProfile.api._

import scala.collection.JavaConverters._
import scala.util._

/* Used by all routes classes to Authenticates the client credentials and then checks the ACLs for authorization.
The main authenticate/authorization flow is:
- an api route calls authenticate() which:
  - initiates a login with JAAS, giving it ExchCallbackHandler (which gives it access to the api request
    - calls the login() methods of each module listed in resources/jaas.config until 1 doesnt throw an exception
      - in IbmCloudModule.login() it calls IbmCloudAuth.authenticateUser()
      - in Module.login() it calls Identity.authenticate()
  - returns an AuthenticatedIdentity (that contains both the exchange-specific Identity, and the JAAS Subject)
- from the return of authenticate() the route then calls AuthenticatedIdentity.authorizeTo() with the target and access required
  - calls the correct Identity subclass authorizeTo() method
    - determines the specific access required and returns a RequiresAccess object
  - calls the as() method on the RequiresAccess object, giving it the Subject
    - calls Subject.doAsPrivileged()
      - calls Module.PermissionCheck.run(), giving it the specific access required (permission)
        - AccessController.checkPermission()
          - i think this looks in resources/auth.policy at the roles and accesses defined for each
*/
trait AuthenticationSupport extends ScalatraBase with AuthorizationSupport {
  // We could add a before action with before() {}, but sometimes they need to pass in user/pw, and sometimes id/token
  // I tried using code from http://www.scalatra.org/2.4/guides/http/authentication.html, but it throws an exception.

  def db: Database      // get access to the db object in ExchangeApiApp
  implicit def logger: Logger    // get access to the logger object in ExchangeApiApp

  var migratingDb = false     // used to lock everyone out during db migration
  def isDbMigration = migratingDb
  // def setDbMigration(dbMigration: Boolean): Unit = { migratingDb = dbMigration }

  /* Used to authenticate and log all the routes, returning an authenticated Identity, which can
   * be used for authorization, or halting the request due to invalid credentials.
   */
  def authenticate(anonymousOk: Boolean = false, hint: String = ""): AuthenticatedIdentity = {
    /*
     * For JAAS, the LoginContext is what you use to attempt to login a user
     * and get a Subject back. It takes care of creating the LoginModules and
     * calling them. It is configured by the jaas.config file, which specifies
     * which LoginModules to use.
     */
    val loginCtx = new LoginContext(
      "ExchangeApiLogin",  // this is referencing a stanza in resources/jaas.config
      new ExchCallbackHandler(RequestInfo(request, params, isDbMigration, anonymousOk, hint))
    )
    for (err <- Try(loginCtx.login()).failed) {
      // An auth exception, log it, then halt
      try {
        val creds = getCredentials(request, params, anonymousOk)   // can throw InvalidCredentialsException
        val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us
        //logger.trace("in AuthenticationSupport.authenticate: err="+err)
        val (httpCode, apiResponse, msg) = AuthErrors.message(err)
        logger.error("User or id " + creds.id + " from " + clientIp + " running " + request.getMethod + " " + request.getPathInfo + ": " + apiResponse + ": " + msg)
        halt(httpCode, ApiResponse(apiResponse, msg))
      } catch {
        case e: Exception => val (httpCode, apiResponse, msg) = AuthErrors.message(e)
          halt(httpCode, ApiResponse(apiResponse, msg))
      }
    }
    val subject = loginCtx.getSubject
    AuthenticatedIdentity(subject.getPrivateCredentials(classOf[Identity]).asScala.head, subject)
  }

  // Only used by unauthenticated (anonymous) rest api methods
  def credsAndLogForAnonymous() = {
    val clientIp = request.header("X-Forwarded-For").orElse(Option(request.getRemoteAddr)).get      // haproxy inserts the real client ip into the header for us

    val feCreds = frontEndCredsForAnonymous()
    if (feCreds != null) {
      logger.info("User or id "+feCreds.id+" from "+clientIp+" (via front end) running "+request.getMethod+" "+request.getPathInfo)
    }
    // else, fall thru to the next section

    // Get the creds from the header or params
    val creds = credsForAnonymous()
    val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
    logger.info("User or id "+userOrId+" from "+clientIp+" running "+request.getMethod+" "+request.getPathInfo)
    if (isDbMigration && !Role.isSuperUser(creds.id)) halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, ExchangeMessage.translateMessage("db.migration.in.progress")))
  }

  def frontEndCredsForAnonymous(): Creds = {
    val frontEndHeader = ExchConfig.config.getString("api.root.frontEndHeader")
    if (frontEndHeader == "" || request.getHeader(frontEndHeader) == null) return null
    logger.trace("request.headers: "+request.headers.toString())
    //todo: For now the only front end we support is data power doing the authentication and authorization. Create a plugin architecture.
    // Data power calls us similar to: curl -u '{username}:{password}' 'https://{serviceURL}' -H 'type:{subjectType}' -H 'id:{username}' -H 'orgid:{org}' -H 'issuer:IBM_ID' -H 'Content-Type: application/json'
    // type: person (user logged into the dashboard), app (API Key), or dev (device/gateway)
    //val idType = request.getHeader("type")
    val orgid = request.getHeader("orgid")
    val id = request.getHeader("id")
    if (id == null || orgid == null) halt(HttpCode.INTERNAL_ERROR, ApiResponse(ApiResponseType.INTERNAL_ERROR, ExchangeMessage.translateMessage("required.headers.not.set", frontEndHeader)))
    val creds = Creds(OrgAndIdCred(orgid,id).toString, "")    // we don't have a pw/token, so leave it blank
    return creds
  }

  /** Looks in the http header and url params for credentials and returns them. Currently only used by credsAndLog(),
   * which is only used for unauthenticated (anonymous) APIs. Supported:
   * Basic auth in header in clear text: Authorization:Basic <user-or-id>:<pw-or-token>
   * Basic auth in header base64 encoded: Authorization:Basic <base64-encoded-of-above>
   * URL params: username=<user>&password=<pw>
   * URL params: id=<id>&token=<token>
   */
  def credsForAnonymous(): Creds = {
    try {
      getCredentials(request, params, anonymousOk = true)   // can throw InvalidCredentialsException
    } catch {
      case e: Exception => val (httpCode, apiResponse, msg) = AuthErrors.message(e)
        halt(httpCode, ApiResponse(apiResponse, msg))
    }
  }

  /** Returns a temporary pw reset token. */
  def createToken(username: String): String = {
    // Get their current pw to use as the secret
    AuthCache.ids.getOne(username) match {
      case Some(userHashedTok) => Token.create(userHashedTok)   // always create the token with the hashed pw because that will always be there during creation and validation of the token
      case None => ""    // this case will never happen (we always pass in superUser), but here to remove compile warning
    }
  }
}

/** Hash a password or token, and compare a pw/token to its hashed value */
object Password {
  // Using jbcrypt, see https://github.com/jeremyh/jBCrypt and http://javadox.com/org.mindrot/jbcrypt/0.3m/org/mindrot/jbcrypt/BCrypt.html
  val logRounds = 10      // hashes the pw 2**logRounds times

  /** Returns the hashed value of the given password or token.
    * Note: since BCrypt.hashpw() uses a different salt each time, 2 hashes of the same pw will be different. So it is not valid to hash the
    *     clear pw specified by the user and compare it to the already-hashed pw in the db. You must use BCrypt.checkpw() instead.
    */
  //TODO: when we have a more reliable check() below, allow them to pass in a pw that is already hashed our way, recognize it, and do not hash it. Linux pw hash can be created using: openssl passwd -1 -salt xyz yourpass
  def hash(password: String): String = { BCrypt.hashpw(password, BCrypt.gensalt(logRounds)) }

  /** Returns true if plainPw matches hashedPw */
  def check(plainPw: String, hashedPw: String): Boolean = { BCrypt.checkpw(plainPw, hashedPw) }

  /** Returns true if this pw/token is already hashed */
  def isHashed(password: String): Boolean = { password.startsWith("""$2a$10$""") }      // is there a better way to determine this?

  /** If already hash, return it, otherwise hash it */
  def hashIfNot(password: String): String = if (isHashed(password)) password else hash(password)
}

/** Create and validate web tokens that expire */
object Token {
  // From: https://github.com/pauldijou/jwt-scala
  val defaultExpiration = 600     // seconds
  val algorithm = JwtAlgorithm.HS256

  /** Returns a temporary pw reset token. */
  def create(secret: String, expiration: Int = defaultExpiration): String = {
    //implicit val clock: Clock = Clock.systemUTC()
    //Jwt.encode(JwtClaim({"""{"user":1}"""}).issuedNow.expiresIn(defaultExpiration), secret, algorithm)
    Jwt.encode(JwtClaim({"""{"user":1}"""}).expiresAt(ApiTime.nowSeconds+expiration), secret, algorithm)
  }

  /** Returns true if the token is correct for this secret and not expired */
  def isValid(token: String, secret: String): Boolean = { Jwt.isValid(token, secret, Seq(algorithm)) }
}
