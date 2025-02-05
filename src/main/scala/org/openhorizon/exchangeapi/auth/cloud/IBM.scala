package org.openhorizon.exchangeapi.auth.cloud

import com.google.common.cache
import com.google.common.cache.CacheBuilder
import org.apache.pekko.event.LoggingAdapter
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.openhorizon.exchangeapi.ExchangeApi
import org.openhorizon.exchangeapi.auth._
import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.{ApiTime, Configuration, ExchMsg, HttpCode}
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._
import scalaj.http._

import java.io.{BufferedInputStream, File, FileInputStream}
import java.security.KeyStore
import java.security.cert.{Certificate, CertificateFactory}
import java.util.concurrent.TimeUnit
import javax.net.ssl.{SSLContext, SSLSocketFactory, TrustManagerFactory}
import javax.security.auth._
import javax.security.auth.callback._
import javax.security.auth.login.FailedLoginException
import javax.security.auth.spi.LoginModule
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

// Credentials specified in the client request
final case class IamAuthCredentials(org: String, keyType: String, key: String) {
  def cacheKey: String = org + "/" + keyType + ":" + key
}
final case class IamToken(accessToken: String, tokenType: Option[String] = None)

// Info retrieved about the user from IAM (using the iam key or token).
// For both IBM Cloud and ICP. All the rest apis that use this must be able to parse their results into this class.
// The account field is set when using IBM Cloud, iss is set when using ICP.
final case class IamUserInfo(account: Option[IamAccount], sub: Option[String], iss: Option[String], active: Option[Boolean]) { // Note: used to use the email field for ibm cloud, but switched to sub because it is common to both
  def accountId: String = if (account.isDefined) account.get.bss else ""
  def isActive: Boolean = active.getOrElse(false)
  def user: String = sub.getOrElse("")
}
final case class IamAccount(bss: String)

/*
  Represents information from one IAM Account from the Multitenancy APIs - More information here: https://www.ibm.com/support/knowledgecenter/SSHKN6/iam/3.x.x/apis/mt_apis.html
    "id": "id-account",
    "name": "my Account",
    "description": "Description for Account",
    "createdOn": "2020-09-15T00:20:43.853Z"
 */
final case class IamAccountInfo(id: String, name: String, description: String, createdOn: String)

//case class TokenAccountResponse(id: String, name: String, description: String)
//s"ICP IAM authentication succeeded, but the org specified in the request ($requestOrg) does not match the org associated with the ICP credentials ($userCredsOrg)"

/**
 * JAAS module to authenticate to the IBM cloud. Called from AuthenticationSupport:authenticate() because JAAS.config references this module.
 */
class IbmCloudModule extends LoginModule with AuthorizationSupport {
  private var subject: Subject = _
  private var handler: CallbackHandler = _
  private var identity: Identity = _
  private var succeeded = false
  def logger: LoggingAdapter = ExchangeApi.defaultLogger
  
  override def abort() = false
  
  override def commit(): Boolean = {
    if (succeeded) {
      subject.getPrivateCredentials().add(identity)
      //subject.getPrincipals().add(ExchangeRole(identity.role)) // don't think we need this
    }
    succeeded
  }
  
  override def initialize(
    subject: Subject,
    handler: CallbackHandler,
    sharedState: java.util.Map[String, _],
    options: java.util.Map[String, _]): Unit = {
    logger.warning("[MKMK] IbmCloudModule initialize")
    this.subject = subject
    this.handler = handler
  }

  override def login(): Boolean = {
    logger.warning("[MKMK] IbmCloudModule login")
    val reqCallback = new RequestCallback

    handler.handle(Array(reqCallback))
    if (reqCallback.request.isEmpty) {
      logger.error("Unable to get HTTP request while authenticating")
    }

    val loginResult: Try[IUser] = for {
      reqInfo <- Try(reqCallback.request.get)   // reqInfo is of type RequestInfo
      user <- {
        //val RequestInfo(_, /*req, _,*/ isDbMigration /*, _*/ , _) = reqInfo
        //val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

        for {
          key <- extractApiKey(reqInfo, Option(reqInfo.hint)) // this will bail out of the outer for loop if the user isn't iamapikey or iamapitoken
          username <- IbmCloudAuth.authenticateUser(key, Option(reqInfo.hint))
        } yield {
          val user: IUser = IUser(Creds(username, ""))
          //logger.info("IBM User " + user.creds.id + " from " + clientIp + " running " + req.getMethod + " " + req.getPathInfo)
          if (reqInfo.isDbMigration && !Role.isSuperUser(user.creds.id)) throw new IsDbMigrationException()
          identity = user // so when the user is authenticating via apikey, we can know the associated username
          user
        }
      }
    } yield user
    succeeded = loginResult.isSuccess
    if (!succeeded) {
      /* If we return either false or an exception, JAAS will move on to the next login module (Module for authenticating local exchange users).
       The difference is: if we return false it means this ibmCloudModule didn't apply, so then if Module ends up throwing an exception it will be reported.
       On the other hand, if this ibmCloudModule returns an exception and Module does too, JAAS will only report the 1st one. */
      loginResult.failed.get match {
        // This was not an ibm cred, so return false so JAAS will move on to the next login module and return any exception from it
        case _: NotIbmCredsException => return false
        // This looked like an ibm cred, but there was a problem with it, so throw the exception so it gets back to the user
        case e: AuthException => throw e
        case e => throw e   // Note: try to avoid creating a generic Throwables, because ApiUtils:AuthRejection() will turn that into an invalid creds rejection
      }
    }
    succeeded
  }

  // Add the real identify of the api key in the subject so we can get it later during authorization
  override def logout(): Boolean = {
    subject.getPrivateCredentials().add(identity)
    true
  }
  
  
  private def extractApiKey(reqInfo: RequestInfo, hint: Option[String]): Try[IamAuthCredentials] = {
    //val creds = credentials(reqInfo)
    val creds: Creds = reqInfo.creds
    val (org, id) = IbmCloudAuth.compositeIdSplit(creds.id)
    if (id != "iamapikey" && id != "iamtoken") Failure(new NotIbmCredsException)
    else if (org == "") {
      if (hint.getOrElse("") == "exchangeNoOrgForMultLogin") {
        logger.warning("[MKMK] IBM autentication route 1. ORG: " + org + ", USER: " + id + ", TOKEN: " + reqInfo.creds.token)
        Success(IamAuthCredentials(null, id, creds.token))
      }
      else Failure(new OrgNotSpecifiedException)
    }
    else if (creds.token.nonEmpty) {
      logger.warning("[MKMK] IBM autentication route 2. ORG: " + org + ", USER: " + id + ", TOKEN: " + reqInfo.creds.token)
      Success(IamAuthCredentials(org, id, creds.token))
    }
    else Failure(new NotIbmCredsException)
  }
}

// Utilities for managing the ibm auth cache and authenticating with ibm
object IbmCloudAuth {
  import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._

  //import scala.concurrent.ExecutionContext.Implicits.global
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext

  private var db: Database = _

  private implicit val formats: DefaultFormats.type = DefaultFormats

  def logger: LoggingAdapter = ExchangeApi.defaultLogger

  private val guavaCache: cache.Cache[String, Entry[String]] = CacheBuilder.newBuilder()
                                                                           .maximumSize(Configuration.getConfig.getInt("api.cache.IAMusersMaxSize"))
                                                                           .expireAfterWrite(Configuration.getConfig.getInt("api.cache.IAMusersTtlSeconds"), TimeUnit.SECONDS)
                                                                           .build[String, Entry[String]] // the cache key is <org>/<keytype>:<apikey> (where keytype is iamapikey or iamtoken), and the value is <org>/<username>
  implicit val userCache: GuavaCache[String] = GuavaCache(guavaCache) // the effect of this is that these methods don't need to be qualified

  // Called by ExchangeApiApp after db is established and upgraded
  def init(db: Database): Unit = {
    this.db = db
  }

  def authenticateUser(authInfo: IamAuthCredentials, hint: Option[String]): Try[String] = {
    logger.debug("authenticateUser(): attempting to authenticate with IBM Cloud with " + authInfo.org + "/" + authInfo.keyType)

    /*
     * This caching function takes a key and tries to retrieve it from the cache. If it is not found it runs the block of code provided,
     * adds the result to the cache, and then returns it. We use cachingF so that we can return a Try value.
     * See http://cb372.github.io/scalacache/docs/#basic-cache-operations for more info.
     */
    cachingF(authInfo.cacheKey)(ttl = None) {
      for {
        token <-
          if (authInfo.keyType == "iamtoken")
            Success(IamToken(authInfo.key))
          else
            getIamToken(authInfo)
        userInfo <- getUserInfo(token, authInfo)
        user <- getOrCreateUser(authInfo.org, userInfo.user, userInfo.accountId, authInfo.keyType, Option(hint.getOrElse("")))
      } yield user.username // this is the composite org/username
    }
  }

  // Note: the cache key is <org>/<keytype>:<apikey>, so in the rest of the code it is usually hard to know this value, except in the auth code path
  def removeUserKey(cacheKey: String): Unit = {
    remove(cacheKey) // does not throw an error if it doesn't exist
  }

  def clearCache(): Try[Unit] = {
    logger.debug(s"Clearing the IBM Cloud auth cache")
    removeAll().map(_ => ()) // i think this map() just transforms the removeAll() return of a future into Unit
  }
  
  private def iamRetryNum = 5

  // Use the IBM IAM API to authenticate the iamapikey and get an IAM token. See: https://cloud.ibm.com/apidocs/iam-identity-token-api
  private def getIamToken(authInfo: IamAuthCredentials): Try[IamToken] = {
    if (authInfo.keyType == "iamapikey") {
      // An IBM Cloud IAM platform api key
      var delayedReturn: Try[IamToken] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET token", iamRetryNum)))
      for (i <- 1 to iamRetryNum) {
        try {
          val iamUrl = "https://iam.cloud.ibm.com/identity/token"
          logger.info("Attempt " + i + " retrieving IBM Cloud IAM token for " + authInfo.org + "/iamapikey from " + iamUrl)
          val response: HttpResponse[String] = Http(iamUrl)
            .header("Accept", "application/json")
            .postForm(Seq(
              "grant_type" -> "urn:ibm:params:oauth:grant-type:apikey",
              "apikey" -> authInfo.key))
            .asString
          if (response.code == HttpCode.OK.intValue) return Success(parse(response.body).camelizeKeys.extract[IamToken])
          else if (response.code == HttpCode.BAD_INPUT.intValue || response.code == HttpCode.BADCREDS.intValue || response.code == HttpCode.ACCESS_DENIED.intValue || response.code == HttpCode.NOT_FOUND.intValue) {
            // This IAM API returns BAD_INPUT (400) when the mechanics of the api call were successful, but the api key was invalid
            return Failure(new InvalidCredentialsException(response.body))
          } else delayedReturn = Failure(new IamApiErrorException(response.body))
        } catch {
          case e: Exception => delayedReturn = Failure(new IamApiErrorException(ExchMsg.translate("error.getting.iam.token.from.api.key", e.getMessage)))
        }
      }
      delayedReturn // if we tried the max times and never got a successful positive or negative, return what we last got
    } else {
      Failure(new AuthInternalErrorException(ExchMsg.translate("no.valid.iam.keyword")))
    }
  }

  // Using the IAM token get the ibm cloud account id (which we'll use to verify the exchange org) and users email (which we'll use as the exchange user)
  // For ICP IAM see: https://github.ibm.com/IBMPrivateCloud/roadmap/blob/master/feature-specs/security/security-services-apis.md
  private def getUserInfo(token: IamToken, authInfo: IamAuthCredentials): Try[IamUserInfo] = {
    // An ibm public cloud token, either from the UI or from the platform apikey we were given
    var delayedReturn: Try[IamUserInfo] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET identity/userinfo", iamRetryNum)))
    for (i <- 1 to iamRetryNum) {
      try {
        val iamUrl = "https://iam.cloud.ibm.com/identity/userinfo"
        logger.info("Attempt " + i + " retrieving IBM Cloud IAM userinfo for " + authInfo.org + "/iamtoken from " + iamUrl)
        val response: HttpResponse[String] =
          Http(iamUrl).header("Authorization", s"BEARER ${token.accessToken}")
                      .header("Content-Type", "application/json")
                      .asString
        logger.debug(iamUrl + " http code: " + response.code + ", body: " + response.body)
        if (response.code == HttpCode.OK.intValue) {
          // This api returns 200 even for an invalid token. Have to determine its validity via the 'active' field
          val userInfo: IamUserInfo = parse(response.body).extract[IamUserInfo]
          if (userInfo.isActive && userInfo.user != "") return Success(userInfo)
          else return Failure(new InvalidCredentialsException(ExchMsg.translate("invalid.iam.token")))
        } else delayedReturn = Failure(new IamApiErrorException(response.body))
      }
      catch {
          case e: Exception => delayedReturn = Failure(new IamApiErrorException(ExchMsg.translate("error.authenticating.iam.token", e.getMessage)))
      }
    }
    delayedReturn // if we tried the max times and never got a successful positive or negative, return what we last got
  }
  

  def getOrCreateUser(orgId: String, username: String, accountId: String, keyType: String, hint: Option[String]): Try[UserRow] = {
    logger.debug("Getting or creating exchange user from DB using IAM userinfo: " + username + ":" + accountId)
    // Form a DB query with the right logic to verify the org and either get or create the user.
    // This can throw exceptions OrgNotFound or IncorrectOrgFound
    val userQuery =
      for {
        //orgAcctId <- fetchOrg(orgId)
        //orgId <- verifyOrg(authInfo, userInfo, orgAcctId) // verify the org exists in the db, and in the public cloud case the cloud acct id of the apikey and the org entry match
        orgId <- fetchVerifyOrg(orgId, accountId, keyType) // verify the org exists in the db, and in the public cloud case the cloud acct id of the apikey and the org entry match
        userRow <- fetchUser(orgId, username)
        userAction <- {
          logger.debug(s"userRow: $userRow")
          if (userRow.isEmpty) createUser(orgId, username)
          else if(userRow.get.orgid == "root" && !userRow.get.hubAdmin) DBIO.failed(new InvalidCredentialsException(ExchMsg.translate("user.cannot.be.in.root.org"))) // only need to check hubadmin field because the root user is by default a hubadmin and org admins are not in the root org
          else DBIO.successful(Success(userRow.get)) // to produce error case below, instead use: createUser(orgId, userInfo)
        }
        // This is to just handle errors from createUser
        userAction2 <- userAction match {
          case Success(v) => DBIO.successful(Success(v))
          case Failure(t) =>
            if (t.getMessage.contains("duplicate key")) {
              // This is the case in which between the call to fetchUser and createUser another client created the user, so this is a duplicate that is not needed.
              // Instead of returning an error just create an artificial response in which the username is correct (that's the only field used from this).
              // (We don't want to do an upsert in createUser in case the user has changed it since it was created, e.g. set admin true)
              DBIO.successful(Success(UserRow(orgId + "/" + username, "", "", admin = false, hubAdmin = false, "", "", "")))
            } else {
              DBIO.failed(new UserCreateException(ExchMsg.translate("error.creating.user", orgId, username, t.getMessage)))

            }
        }
      } yield userAction2

    val awaitResult: Success[UserRow] =
      try {
        //logger.debug("awaiting for DB query of ibm cloud creds for "+orgId+"/"+username+"...")
        if (hint.getOrElse("") == "exchangeNoOrgForMultLogin") {
          Success(UserRow(username, "", "", admin = false, hubAdmin = false, "", "", ""))
        } else Await.result(db.run(userQuery.transactionally), Duration(Configuration.getConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.debug("...back from awaiting for DB query of ibm cloud creds for "+orgId+"/"+username+".")
      } catch {
        // Handle any exceptions, including db problems. Note: exceptions from this get caught in login() above
        case timeout: java.util.concurrent.TimeoutException =>
          logger.error("db timed out getting pw/token for '" + username + "' . " + timeout.getMessage)
          throw new DbTimeoutException(ExchMsg.translate("db.timeout.getting.token", username, timeout.getMessage))
        // catch any of ours and rethrow
        case ourException: AuthException => throw ourException
        // assume something we don't recognize is a db access problem
        case other: Throwable =>
          logger.error("db connection error getting pw/token for '" + username + "': " + other.getMessage)
          throw new DbConnectionException(ExchMsg.translate("db.threw.exception", other.getMessage))
      }
    // Note: not sure how to know here whether we successfully add a user and therefore should add it to the admin cache, so we'll just let that get added next time it is needed
    awaitResult
  }

  // Verify that the cloud acct id of the cloud api key and the exchange org entry match
  // authInfo is the creds they passed in, userInfo is what was returned from the IAM calls, and orgAcctId is what we got from querying the org in the db
  private def fetchVerifyOrg(orgId: String, accountId: String, keyType: String) = {
    // IBM Cloud - we already have the account id from iam from the creds, and the account id of the exchange org
    logger.debug(s"Fetching and verifying IBM public cloud org: $orgId, $accountId")
    OrgsTQ.getOrgid(orgId).map(_.tags.+>>("ibmcloud_id")).result.headOption.flatMap({
      case None =>
        //logger.error(s"IAM authentication succeeded, but no matching exchange org with a cloud account id was found for ${orgId}")
        DBIO.failed(OrgNotFound(orgId))
      case Some(acctIdOpt) => // acctIdOpt is type Option[String]
        logger.debug(s"fetch org acctIdOpt: $acctIdOpt")
        if (keyType == "iamtoken" && accountId == "") {
          //todo: this is the case with tokens from the edge mgmt ui (because we don't get the accountId when we validate an iamtoken). The ui already verified the org and is using the right one,
          //      so there is no exposure there. But we still need to verify the org in case someone is spoofing being the ui. But to get here, the iamtoken has to be valid, just not for this org.
          //      With IECM on ICP/OCP, the only exposure is using an iamtoken from the cluster org to manage resources in the IBM org.
          DBIO.successful(orgId)
        } else if (acctIdOpt.getOrElse("") != accountId) {
          //logger.error(s"IAM authentication succeeded, but the cloud account id of the org $orgAcctId does not match that of the cloud account ${userInfo.accountId}")
          DBIO.failed(IncorrectOrgFound(orgId, accountId))
        } else {
          DBIO.successful(orgId)
        }
    })
  }

  private def fetchUser(org: String, username: String) = {
    logger.debug("Fetching user: org=" + org + ", " + username)
    UsersTQ
      .filter(u => u.orgid === org && u.username === s"$org/${username}")
      //.take(1)  // not sure what the purpose of this was
      .result
      .headOption
  }

  private def createUser(org: String, username: String) = {
    if(org != null) {
      logger.debug("Creating user: org=" + org + ", " + username)
      val user: UserRow =
        UserRow(username = s"$org/${username}",
                orgid = org,
                hashedPw = "",
                admin = false,
                hubAdmin = false,
                email = username,
                lastUpdated = ApiTime.nowUTC,
                updatedBy = s"$org/${username}")
      (UsersTQ += user).asTry.map(count => count.map(_ => user))
    } else {
      logger.debug("ibmcloudmodule not creating user")
      null
    }
  }

  // Split an id in the form org/id and return both parts. If there is no / we assume it is an id without the org.
  def compositeIdSplit(compositeId: String): (String, String) = {
    val reg: Regex = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org, id) => (org, id)
      // These 2 lines never get run, and aren't needed. If we really want to handle a special, put something like this as the 1st case above: case reg(org, "") => return (org, "")
      //case reg(org, _) => return (org, "")
      //case reg(_, id) => return ("", id)
      case _ => ("", compositeId)
    }
  }
}

/**
 * JAAS module to authenticate local user/pw, nodeid/token, and agbotid/token in the exchange.
 * Called from AuthenticationSupport:authenticate() because JAAS.config references this module.
 */
class IeamUiAuthenticationModule extends LoginModule with AuthorizationSupport {
  private var subject: Subject = _
  private var handler: CallbackHandler = _
  private var identity: Identity = _
  private var succeeded = false
  def logger = ExchangeApi.defaultLogger

  override def initialize(
    subject: Subject,
    handler: CallbackHandler,
    sharedState: java.util.Map[String, _],
    options: java.util.Map[String, _]): Unit = {
    logger.warning("[MKMK] IeamUiAuthenticationModule initialize")
    this.subject = subject
    this.handler = handler
  }

  /* TODO mkmk
   * This is where the actual login logic is performed, and is called by the
   * LoginContext when its login method is called. This uses the callback to
   * get acces to the web request, and then uses the logic from the credsAndLog
   * to get an Identity from the request. This is later attached to the subject
   * in the commit method, which is called by the context after login succeeds,
   * and that is where we can get access to it in the route handling code.
   */
  override def login(): Boolean = {
    logger.warning("[MKMK] IeamUiAuthenticationModule login")
    //logger.debug("in Module.login() to try to authenticate a local exchange user")
    val reqCallback = new RequestCallback
    val loginResult = Try {
      handler.handle(Array(reqCallback))
      if (reqCallback.request.isEmpty) {
        logger.debug("Unable to get HTTP request while authenticating")
        throw new AuthInternalErrorException(ExchMsg.translate("unable.to.get.http.request.when.authenticating"))
      }
      val reqInfo = reqCallback.request.get 
      val (org, id) = IbmCloudAuth.compositeIdSplit(reqInfo.creds.id)

      if (org == "") throw new OrgNotSpecifiedException
      if (reqInfo.isDbMigration && !Role.isSuperUser(reqInfo.creds.id)) throw new IsDbMigrationException()
      
      if (id == "iamapikey" || id == "iamtoken") throw new NotIeamUiCredsException
      if (!reqInfo.creds.token.startsWith("ieam-")) throw new NotIeamUiCredsException

      
      logger.warning("[MKMK] IEAM autentication route. ORG: " + org + ", USER: " + id + ", TOKEN: " + reqInfo.creds.token)

      // IIdentity(Creds("IBM/ieam", reqInfo.creds.token.replace("ieam-", ""))).authenticate() // TODO mkmk

      logger.warning("[MKMK] IEAM autentication route. Authenticate successful.")

      IbmCloudAuth.getOrCreateUser(org, id, "id-mycluster-account", "iamtoken", Option(reqInfo.hint)) // TODO mkmk: 2nd and 3rd param
      identity = IUser(Creds(reqInfo.creds.id, "ieam-ui-password-placeholder"))
      true
    }
    //logger.debug("Module.login(): loginResult=" + loginResult)
    succeeded = loginResult.isSuccess
    if (!succeeded) {
      // Throw an exception so we can report the correct error
      loginResult.failed.get match {
        case _: NotIeamUiCredsException => return false
        case e: AuthException => throw e
        case _ => throw new FailedLoginException
      }
    }
    succeeded
  }

  override def logout(): Boolean = {
    subject.getPrivateCredentials().add(identity)
    true
  }

  override def abort() = false

  override def commit(): Boolean = {
    if (succeeded) {
      subject.getPrivateCredentials().add(identity)
    }
    succeeded
  }

}