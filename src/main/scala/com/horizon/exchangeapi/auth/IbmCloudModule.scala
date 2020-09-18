package com.horizon.exchangeapi.auth

import java.io.{BufferedInputStream, File, FileInputStream}
import java.security.cert.{Certificate, CertificateFactory}
import java.util.concurrent.TimeUnit
import java.security.KeyStore

import com.google.common.cache.CacheBuilder
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.auth.IbmCloudAuth.logger
import com.horizon.exchangeapi.tables.{OrgsTQ, UserRow, UsersTQ}
import javax.net.ssl.{SSLContext, SSLSocketFactory, TrustManagerFactory}
import javax.security.auth._
import javax.security.auth.callback._
import javax.security.auth.spi.LoginModule
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.ExecutionContext
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._
import scalaj.http._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

// Credentials specified in the client request
final case class IamAuthCredentials(org: String, keyType: String, key: String) {
  def cacheKey = org + "/" + keyType + ":" + key
}
final case class IamToken(accessToken: String, tokenType: Option[String] = None)

// Info retrieved about the user from IAM (using the iam key or token).
// For both IBM Cloud and ICP. All the rest apis that use this must be able to parse their results into this class.
// The account field is set when using IBM Cloud, iss is set when using ICP.
final case class IamUserInfo(account: Option[IamAccount], sub: Option[String], iss: Option[String], active: Option[Boolean]) { // Note: used to use the email field for ibm cloud, but switched to sub because it is common to both
  def accountId = if (account.isDefined) account.get.bss else ""
  def isActive = active.getOrElse(false)
  def user = sub.getOrElse("")
}
final case class IamAccount(bss: String)

// Response from /api/v1/config
final case class ClusterConfigResponse(cluster_name: String, cluster_url: String, cluster_ca_domain: String, kube_url: String, helm_host: String)

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
  def logger = ExchangeApi.defaultLogger

  override def initialize(
    subject: Subject,
    handler: CallbackHandler,
    sharedState: java.util.Map[String, _],
    options: java.util.Map[String, _]): Unit = {
    this.subject = subject
    this.handler = handler
  }

  override def login(): Boolean = {
    //logger.debug("in IbmCloudModule.login() to try to authenticate an IBM cloud user")
    val reqCallback = new RequestCallback

    handler.handle(Array(reqCallback))
    if (reqCallback.request.isEmpty) {
      logger.error("Unable to get HTTP request while authenticating")
    }

    val loginResult = for {
      reqInfo <- Try(reqCallback.request.get)   // reqInfo is of type RequestInfo
      user <- {
        //val RequestInfo(_, /*req, _,*/ isDbMigration /*, _*/ , _) = reqInfo
        //val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

        for {
          key <- extractApiKey(reqInfo, Some(reqInfo.hint)) // this will bail out of the outer for loop if the user isn't iamapikey or iamapitoken
          username <- IbmCloudAuth.authenticateUser(key, Some(reqInfo.hint))
        } yield {
          val user = IUser(Creds(username, ""))
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

  override def abort() = false

  override def commit(): Boolean = {
    if (succeeded) {
      subject.getPrivateCredentials().add(identity)
      //subject.getPrincipals().add(ExchangeRole(identity.role)) // don't think we need this
    }
    succeeded
  }

  private def extractApiKey(reqInfo: RequestInfo, hint: Option[String]): Try[IamAuthCredentials] = {
    //val creds = credentials(reqInfo)
    val creds = reqInfo.creds
    val (org, id) = IbmCloudAuth.compositeIdSplit(creds.id)
    if (org == "") {
      if (hint.getOrElse("") == "exchangeNoOrgForMultLogin") Success(IamAuthCredentials(null, id, creds.token))
      else Failure(new OrgNotSpecifiedException)
    }
    else if ((id == "iamapikey" || id == "iamtoken") && creds.token.nonEmpty) Success(IamAuthCredentials(org, id, creds.token))
    else Failure(new NotIbmCredsException)
  }
}

// Utilities for managing the ibm auth cache and authenticating with ibm
object IbmCloudAuth {
  import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

  //import scala.concurrent.ExecutionContext.Implicits.global
  implicit def executionContext: ExecutionContext = ExchangeApi.defaultExecutionContext

  private var db: Database = _
  private var sslSocketFactory: SSLSocketFactory = _
  private var icpClusterNameTry: Try[String] = _
  private var icpClusterNameNumRetries = 0

  private implicit val formats = DefaultFormats

  def logger = ExchangeApi.defaultLogger

  private val guavaCache = CacheBuilder.newBuilder()
    .maximumSize(ExchConfig.getInt("api.cache.idsMaxSize"))
    .expireAfterWrite(ExchConfig.getInt("api.cache.idsTtlSeconds"), TimeUnit.SECONDS)
    .build[String, Entry[String]] // the cache key is org/apikey, and the value is org/username
  implicit val userCache = GuavaCache(guavaCache) // the effect of this is that these methods don't need to be qualified

  // Called by ExchangeApiApp after db is established and upgraded
  def init(db: Database): Unit = {
    this.db = db
    logger.info(s"IBM authentication-related env vars: PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT=${sys.env.get("PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT")}, ICP_EXTERNAL_MGMT_INGRESS=${sys.env.get("ICP_EXTERNAL_MGMT_INGRESS")}, ICP_MANAGEMENT_INGRESS_SERVICE_PORT=${sys.env.get("ICP_MANAGEMENT_INGRESS_SERVICE_PORT")}")
    if (isIcp) {
      this.sslSocketFactory = buildSslSocketFactory(getIcpCertFile)
      getIcpClusterName   // this caches the result in member var icpClusterNameTry
      logger.info(s"ICP cluster name: $icpClusterNameTry")
    }
  }

  def authenticateUser(authInfo: IamAuthCredentials, hint: Option[String]): Try[String] = {
    logger.debug("authenticateUser(): attempting to authenticate with IBM Cloud with " + authInfo.org + "/" + authInfo.keyType)

    /*
     * The caching library provides several functions that work on the cache defined above. The caching function takes a key and tries
     * to retrieve from the cache, and if it is not there runs the block of code provided, adds the result to the cache, and then returns it.
     * I use cachingF here so that I can return a Try value (see http://cb372.github.io/scalacache/docs/#basic-cache-operations for more info)
     */
    cachingF(authInfo.cacheKey)(ttl = None) {
      for {
        token <- if (authInfo.keyType == "iamtoken") Success(IamToken(authInfo.key))
        else if (isIcp && authInfo.keyType == "iamapikey") Success(IamToken(authInfo.key, Some(authInfo.keyType))) // this is an apikey we are putting in IamToken, but it can be used like a token in the next step
        else getIamToken(authInfo)
        userInfo <- getUserInfo(token, authInfo)
        user <- getOrCreateUser(authInfo, userInfo, Some(hint.getOrElse("")))
      } yield user.username // this is the composite org/username
    }
  }

  def clearCache(): Try[Unit] = {
    logger.debug(s"Clearing the IBM Cloud auth cache")
    removeAll().map(_ => ())
  }

  // Note: we need these 2 methods because if the env var is set to "", sys.env.get() will return Some("") instead of None
  private def isEnvSet(envVarName: String) = sys.env.get(envVarName) match {
    case Some(v) => v != ""
    case None => false
  }
  private def getEnv(envVarName: String, defaultVal: String) = sys.env.get(envVarName) match {
    case Some(v) => if (v == "") defaultVal else v
    case None => defaultVal
  }

  private def isIcp = {
    // ICP kube automatically sets the 1st one, our development environment sets the 2nd one when locally testing
    //logger.debug("isIcp: ICP_EXTERNAL_MGMT_INGRESS: " + sys.env.get("ICP_EXTERNAL_MGMT_INGRESS"))
    isEnvSet("PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT") || isEnvSet("ICP_EXTERNAL_MGMT_INGRESS")
  }

  private def getIcpIdentityProviderUrl = {
    // https://$ICP_EXTERNAL_MGMT_INGRESS/idprovider  or  https://platform-identity-provider:$PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT
    if (isEnvSet("ICP_EXTERNAL_MGMT_INGRESS")) {
      var ICP_EXTERNAL_MGMT_INGRESS = getEnv("ICP_EXTERNAL_MGMT_INGRESS", "")
      if (!ICP_EXTERNAL_MGMT_INGRESS.startsWith("https://")) ICP_EXTERNAL_MGMT_INGRESS = s"https://$ICP_EXTERNAL_MGMT_INGRESS"
      s"$ICP_EXTERNAL_MGMT_INGRESS/idprovider"
    } else {
      // ICP kube automatically sets this env var and hostname
      val PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT = getEnv("PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT", "4300")
      s"https://platform-identity-provider:$PLATFORM_IDENTITY_PROVIDER_SERVICE_PORT"
    }
  }

  private def getIcpMgmtIngressUrl = {
    // https://$ICP_EXTERNAL_MGMT_INGRESS  or  https://icp-management-ingress.kube-system:$ICP_MANAGEMENT_INGRESS_SERVICE_PORT
    if (isEnvSet("ICP_EXTERNAL_MGMT_INGRESS")) {
      var ICP_EXTERNAL_MGMT_INGRESS = getEnv("ICP_EXTERNAL_MGMT_INGRESS", "")
      if (!ICP_EXTERNAL_MGMT_INGRESS.startsWith("https://")) ICP_EXTERNAL_MGMT_INGRESS = s"https://$ICP_EXTERNAL_MGMT_INGRESS"
      ICP_EXTERNAL_MGMT_INGRESS
    } else {
      // ICP kube automatically sets this env var and hostname
      val ICP_MANAGEMENT_INGRESS_SERVICE_PORT = getEnv("ICP_MANAGEMENT_INGRESS_SERVICE_PORT", "8443")
      s"https://icp-management-ingress.kube-system:$ICP_MANAGEMENT_INGRESS_SERVICE_PORT"
    }
  }

  private def getIcpCertFile = "/etc/horizon/exchange/icp/ca2.crt" // our ICP provisioning creates this file
  private def iamRetryNum = 5

  // Get the ICP cluster name as a Try[String] and cache it in member var icpClusterNameTry.
  private def getIcpClusterName: Try[String] = {
    icpClusterNameTry match {
      case null =>
        icpClusterNameTry = _getIcpClusterName
        icpClusterNameTry
      case Failure(_: IamApiTimeoutException) | Failure(_: IamApiErrorException) =>
        // Getting the cluster name failed before with a retryable error, so retry a few times
        if (icpClusterNameNumRetries < iamRetryNum) {
          icpClusterNameTry = _getIcpClusterName  // Note: this method has retries within it
          icpClusterNameNumRetries += 1
          icpClusterNameTry
        }else {
          icpClusterNameTry   // we have already retried the max times, so we have to live with this error from now on
        }
      case Failure(_) =>
        icpClusterNameTry   // we previously got an error that is not retryable
      case Success(_) =>
        icpClusterNameTry
    }
  }

  // Internal method called from getIcpClusterName
  private def _getIcpClusterName: Try[String] = {
    for (i <- 1 to iamRetryNum) {
      try {
        // just get cluster name - works for both ICP 3.2.0 and 3.2.1
        val iamUrl = getIcpMgmtIngressUrl + "/api/v1/config"
        logger.debug("Attempt " + i + " retrieving ICP IAM cluster name from " + iamUrl)
        val response = Http(iamUrl).method("get").option(HttpOptions.sslSocketFactory(this.sslSocketFactory)).asString
        if (response.code == HttpCode.OK.intValue) {
          val resp = parse(response.body).extract[ClusterConfigResponse]
          val clusterName = resp.cluster_name
          //logger.debug("ICP cluster name: " + clusterName)
          return Success(clusterName)
        } else {
          logger.debug("Cluster name retrieval http code: " + response.code)
          return Failure(new IamApiErrorException(response.body.toString))
        }
      } catch {
        case e: Exception => return Failure(new IamApiErrorException(ExchMsg.translate("error.getting.cluster.name", e.getMessage)))
      }
    }
    return Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET config", iamRetryNum)))
  }

  // Use the IBM IAM API to authenticate the iamapikey and get an IAM token. See: https://cloud.ibm.com/apidocs/iam-identity-token-api
  private def getIamToken(authInfo: IamAuthCredentials): Try[IamToken] = {
    if (authInfo.keyType == "iamapikey") {
      // An IBM Cloud IAM platform api key
      var delayedReturn: Try[IamToken] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET token", iamRetryNum)))
      for (i <- 1 to iamRetryNum) {
        try {
          val iamUrl = "https://iam.cloud.ibm.com/identity/token"
          logger.info("Attempt " + i + " retrieving IBM Cloud IAM token for " + authInfo.org + "/iamapikey from " + iamUrl)
          val response = Http(iamUrl)
            .header("Accept", "application/json")
            .postForm(Seq(
              "grant_type" -> "urn:ibm:params:oauth:grant-type:apikey",
              "apikey" -> authInfo.key))
            .asString
          if (response.code == HttpCode.OK.intValue) return Success(parse(response.body).camelizeKeys.extract[IamToken])
          else if (response.code == HttpCode.BAD_INPUT.intValue || response.code == HttpCode.BADCREDS.intValue || response.code == HttpCode.ACCESS_DENIED.intValue || response.code == HttpCode.NOT_FOUND.intValue) {
            // This IAM API returns BAD_INPUT (400) when the mechanics of the api call were successful, but the api key was invalid
            return Failure(new InvalidCredentialsException(response.body.toString))
          } else delayedReturn = Failure(new IamApiErrorException(response.body.toString))
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
    if (isIcp && token.tokenType.getOrElse("") == "iamapikey") {
      // An icp platform apikey that we can use directly to authenticate and get the username
      var delayedReturn: Try[IamUserInfo] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET introspect", iamRetryNum)))
      for (i <- 1 to iamRetryNum) {
        try {
          val iamUrl = getIcpMgmtIngressUrl + "/iam-token/oidc/introspect"
          logger.info("Attempt " + i + " retrieving ICP IAM userinfo for " + authInfo.org + "/iamapikey from " + iamUrl)
          val apiKey = token.accessToken
          // Have our http client use the ICP self-signed cert so we don't have to use the allowUnsafeSSL option
          val response = Http(iamUrl).method("post").option(HttpOptions.sslSocketFactory(this.sslSocketFactory))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .postData("apikey=" + apiKey)
            .asString
          logger.debug(iamUrl + " http code: " + response.code + ", body: " + response.body)
          if (response.code == HttpCode.OK.intValue) {
            // This api returns 200 even for an invalid api key. Have to determine its validity via the 'active' field
            val userInfo = parse(response.body).extract[IamUserInfo]
            if (userInfo.isActive && userInfo.user != "") return Success(userInfo)
            else return Failure(new InvalidCredentialsException(ExchMsg.translate("invalid.iam.api.key")))
          } else delayedReturn = Failure(new IamApiErrorException(response.body.toString))
        } catch {
          case e: Exception => delayedReturn = Failure(new IamApiErrorException(ExchMsg.translate("error.authenticating.icp.iam.key", e.getMessage)))
        }
      }
      delayedReturn // if we tried the max times and never got a successful positive or negative, return what we last got
    } else if (isIcp) {
      // An icp token from the UI
      var delayedReturn: Try[IamUserInfo] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET userinfo", iamRetryNum)))
      for (i <- 1 to iamRetryNum) {
        try {
          val iamUrl = getIcpIdentityProviderUrl + "/v1/auth/userinfo"
          //logger.debug("Retrieving ICP IAM userinfo from " + iamUrl + ", token: " + token.accessToken)
          logger.info("Attempt " + i + " retrieving ICP IAM userinfo for " + authInfo.org + "/iamtoken from " + iamUrl)
          val response = Http(iamUrl).method("post").option(HttpOptions.sslSocketFactory(this.sslSocketFactory))
            .header("Authorization", s"BEARER ${token.accessToken}")
            .header("Content-Type", "application/json")
            .asString
          logger.debug(iamUrl + " http code: " + response.code + ", body: " + response.body)
          if (response.code == HttpCode.OK.intValue) return Success(parse(response.body).extract[IamUserInfo])
          else if (response.code == HttpCode.BAD_INPUT.intValue || response.code == HttpCode.BADCREDS.intValue || response.code == HttpCode.ACCESS_DENIED.intValue || response.code == HttpCode.NOT_FOUND.intValue) {
            // This IAM API returns BAD_INPUT (400) when the mechanics of the api call were successful, but the token was invalid
            return Failure(new InvalidCredentialsException(response.body.toString))
          } else delayedReturn = Failure(new IamApiErrorException(response.body.toString))
        } catch {
          case e: Exception => delayedReturn = Failure(new IamApiErrorException(ExchMsg.translate("error.authenticating.icp.iam.token", e.getMessage)))
        }
      }
      delayedReturn // if we tried the max times and never got a successful positive or negative, return what we last got
    } else {
      // An ibm public cloud token, either from the UI or from the platform apikey we were given
      var delayedReturn: Try[IamUserInfo] = Failure(new IamApiTimeoutException(ExchMsg.translate("iam.return.value.not.set", "GET identity/userinfo", iamRetryNum)))
      for (i <- 1 to iamRetryNum) {
        try {
          val iamUrl = "https://iam.cloud.ibm.com/identity/userinfo"
          logger.info("Attempt " + i + " retrieving IBM Cloud IAM userinfo for " + authInfo.org + "/iamtoken from " + iamUrl)
          val response = Http(iamUrl)
            .header("Authorization", s"BEARER ${token.accessToken}")
            .header("Content-Type", "application/json")
            .asString
          logger.debug(iamUrl + " http code: " + response.code + ", body: " + response.body)
          if (response.code == HttpCode.OK.intValue) {
            // This api returns 200 even for an invalid token. Have to determine its validity via the 'active' field
            val userInfo = parse(response.body).extract[IamUserInfo]
            if (userInfo.isActive && userInfo.user != "") return Success(userInfo)
            else return Failure(new InvalidCredentialsException(ExchMsg.translate("invalid.iam.token")))
          } else delayedReturn = Failure(new IamApiErrorException(response.body.toString))
        } catch {
          case e: Exception => delayedReturn = Failure(new IamApiErrorException(ExchMsg.translate("error.authenticating.iam.token", e.getMessage)))
        }
      }
      delayedReturn // if we tried the max times and never got a successful positive or negative, return what we last got
    }
  }

  private def getOrCreateUser(authInfo: IamAuthCredentials, userInfo: IamUserInfo, hint: Option[String]): Try[UserRow] = {
    logger.debug("Getting or creating exchange user from DB using IAM userinfo: " + userInfo)
    // Form a DB query with the right logic to verify the org and either get or create the user.
    // This can throw exceptions OrgNotFound or IncorrectOrgFound
    val userQuery =
      for {
        //orgAcctId <- fetchOrg(authInfo.org)
        //orgId <- verifyOrg(authInfo, userInfo, orgAcctId) // verify the org exists in the db, and in the public cloud case the cloud acct id of the apikey and the org entry match
        orgId <- fetchVerifyOrg(authInfo, userInfo, Some(hint.getOrElse(""))) // verify the org exists in the db, and in the public cloud case the cloud acct id of the apikey and the org entry match
        userRow <- fetchUser(orgId, userInfo)
        userAction <- {
          logger.debug(s"userRow: $userRow")
          if (userRow.isEmpty) createUser(orgId, userInfo)
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
              DBIO.successful(Success(UserRow(authInfo.org + "/" + userInfo.user, "", "", admin = false, hubAdmin = false, "", "", "")))
            } else {
              DBIO.failed(new UserCreateException(ExchMsg.translate("error.creating.user", authInfo.org, userInfo.user, t.getMessage)))

            }
        }
      } yield userAction2

    val awaitResult =
      try {
        //logger.debug("awaiting for DB query of ibm cloud creds for "+authInfo.org+"/"+userInfo.user+"...")
        if (hint.getOrElse("") == "exchangeNoOrgForMultLogin") {
          Success(UserRow(userInfo.user, "", "", admin = false, hubAdmin = false, "", "", ""))
        } else Await.result(db.run(userQuery.transactionally), Duration(ExchConfig.getInt("api.cache.authDbTimeoutSeconds"), SECONDS))
        //logger.debug("...back from awaiting for DB query of ibm cloud creds for "+authInfo.org+"/"+userInfo.user+".")
      } catch {
        // Handle any exceptions, including db problems. Note: exceptions from this get caught in login() above
        case timeout: java.util.concurrent.TimeoutException =>
          logger.error("db timed out getting pw/token for '" + userInfo.user + "' . " + timeout.getMessage)
          throw new DbTimeoutException(ExchMsg.translate("db.timeout.getting.token", userInfo.user, timeout.getMessage))
        // catch any of ours and rethrow
        case ourException: AuthException => throw ourException
        // assume something we don't recognize is a db access problem
        case other: Throwable =>
          logger.error("db connection error getting pw/token for '" + userInfo.user + "': " + other.getMessage)
          throw new DbConnectionException(ExchMsg.translate("db.threw.exception", other.getMessage))
      }
    // Note: not sure how to know here whether we successfully add a user and therefore should add it to the admin cache, so we'll just let that get added next time it is needed
    awaitResult
  }

  // Verify that the cloud acct id of the cloud api key and the exchange org entry match
  // authInfo is the creds they passed in, userInfo is what was returned from the IAM calls, and orgAcctId is what we got from querying the org in the db
  private def fetchVerifyOrg(authInfo: IamAuthCredentials, userInfo: IamUserInfo, hint: Option[String]) = {
    if (isIcp) {
      if (hint.getOrElse("") == "exchangeNoOrgForMultLogin") {
        DBIO.successful(null)
      }
      else {
        // replace this with checking against getUserAccounts that the org they're in matches the org they're trying to access
        logger.debug(s"Fetching and verifying ICP/OCP org: $authInfo, $userInfo, $getIcpClusterName")
        OrgsTQ.getOrgid(authInfo.org).map(_.orgid).result.headOption.flatMap({
          case None =>
            DBIO.failed(OrgNotFound(authInfo.org))
          case Some(result) =>
            // We are here because the client creds (authInfo) were either an icp iamtoken or iamapikey. Those are only valid in the cluster name org (not the IBM org).
            // So confirm that authInfo.org equals the cluster name
            logger.debug(s"fetch org result: $result")
            getIcpClusterName match {
              case Success(clusterName) =>
                if (authInfo.org == clusterName) DBIO.successful(authInfo.org)
                else DBIO.failed(IncorrectIcpOrgFound(authInfo.org, clusterName))
              case Failure(t) =>
                DBIO.failed(t)
            }
        })
      }
    } else {
      // IBM Cloud - we already have the account id from iam from the creds, and the account id of the exchange org
      logger.debug(s"Fetching and verifying IBM public cloud org: $authInfo, $userInfo")
      OrgsTQ.getOrgid(authInfo.org).map(_.tags.+>>("ibmcloud_id")).result.headOption.flatMap({
        case None =>
          //logger.error(s"IAM authentication succeeded, but no matching exchange org with a cloud account id was found for ${authInfo.org}")
          DBIO.failed(OrgNotFound(authInfo.org))
        case Some(acctIdOpt) => // acctIdOpt is type Option[String]
          logger.debug(s"fetch org acctIdOpt: $acctIdOpt")
          if (authInfo.keyType == "iamtoken" && userInfo.accountId == "") {
            //todo: this is the case with tokens from the edge mgmt ui (because we don't get the accountId when we validate an iamtoken). The ui already verified the org and is using the right one,
            //      so there is no exposure there. But we still need to verify the org in case someone is spoofing being the ui. But to get here, the iamtoken has to be valid, just not for this org.
            //      With IECM on ICP/OCP, the only exposure is using an iamtoken from the cluster org to manage resources in the IBM org.
            DBIO.successful(authInfo.org)
          } else if (acctIdOpt.getOrElse("") != userInfo.accountId) {
            //logger.error(s"IAM authentication succeeded, but the cloud account id of the org $orgAcctId does not match that of the cloud account ${userInfo.accountId}")
            DBIO.failed(IncorrectOrgFound(authInfo.org, userInfo.accountId))
          } else {
            DBIO.successful(authInfo.org)
          }
      })
    }
  }

  private def fetchUser(org: String, userInfo: IamUserInfo) = {
    logger.debug("Fetching user: org=" + org + ", " + userInfo)
    UsersTQ.rows
      .filter(u => u.orgid === org && u.username === s"$org/${userInfo.user}")
      //.take(1)  // not sure what the purpose of this was
      .result
      .headOption
  }

  private def createUser(org: String, userInfo: IamUserInfo) = {
    if(org != null) {
      logger.debug("Creating user: org=" + org + ", " + userInfo)
      val user = UserRow(
        s"$org/${userInfo.user}",
        org,
        "",
        admin = false,
        hubAdmin = false,
        userInfo.user,
        ApiTime.nowUTC,
        s"$org/${userInfo.user}")
      (UsersTQ.rows += user).asTry.map(count => count.map(_ => user))
    } else {
      logger.debug("ibmcloudmodule not creating user")
      null
    }
  }

  /* private def extractICPTokenOrg(id: String) = {
    // ICP id field is like: id-major-peacock-icp-cluster-account
    val R = """id-(.+)-account""".r
    id match {
      case R(clusterName) => clusterName
      case _ => ""
    }
  } */

  // Currently not needed for verifying Org in the ICP case
  /*  private def extractICPOrg(iss: String) = {
    // ICP iss value looks like: https://major-peacock-icp-cluster.icp:9443/oidc/token
    val R = """https://(.+)\.icp:.+""".r
    iss match {
      case R(clusterName) => clusterName
      case _ => ""
    }
  } */

  // Split an id in the form org/id and return both parts. If there is no / we assume it is an id without the org.
  def compositeIdSplit(compositeId: String): (String, String) = {
    val reg = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org, id) => return (org, id)
      // These 2 lines never get run, and aren't needed. If we really want to handle a special, put something like this as the 1st case above: case reg(org, "") => return (org, "")
      //case reg(org, _) => return (org, "")
      //case reg(_, id) => return ("", id)
      case _ => return ("", compositeId)
    }
  }

  // Create an SSLSocketFactory that verifies a server using a self-signed cert. Used as example: https://gist.github.com/erickok/7692592
  def buildSslSocketFactory(filePath: String): SSLSocketFactory = {
    try {
      // Load the cert from the file
      val cf = CertificateFactory.getInstance("X.509")
      val caInput = new BufferedInputStream(new FileInputStream(new File(filePath)))
      var ca: Certificate = null
      try {
        ca = cf.generateCertificate(caInput)
        logger.debug("Loading self-signed CA from " + filePath + ", type: " + ca.getType)
      } finally { caInput.close() }

      // Create an in-memory KeyStore containing our self-signed cert
      val keyStoreType = KeyStore.getDefaultType
      val keyStore = KeyStore.getInstance(keyStoreType)
      keyStore.load(null, null)
      keyStore.setCertificateEntry("ca", ca)

      // Create a TrustManager that trusts the CAs in our KeyStore
      val alg = TrustManagerFactory.getDefaultAlgorithm
      val tmf = TrustManagerFactory.getInstance(alg)
      tmf.init(keyStore)

      // Create an SSLContext that uses our TrustManager
      val context = SSLContext.getInstance("TLS")
      context.init(null, tmf.getTrustManagers, null)
      return context.getSocketFactory
    } catch { case e: Exception => throw new SelfSignedCertException(e.getMessage) }
  }
}
