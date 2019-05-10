package com.horizon.exchangeapi.auth

import java.util.concurrent.TimeUnit

import com.google.common.cache.CacheBuilder
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables.{OrgsTQ, UserRow, UsersTQ}
import javax.security.auth._
import javax.security.auth.callback._
import javax.security.auth.spi.LoginModule
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.slf4j.{Logger, LoggerFactory}
import scalacache._
import scalacache.guava.GuavaCache
import scalacache.modes.try_._
import scalaj.http._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class IamAuthCredentials(org: String, keyType: String, key: String) {
  def cacheKey = org + "/" + keyType + ":" + key
}
case class IamToken(accessToken: String, tokenType: Option[String] = None)

// For both IBM Cloud and ICP. All the rest apis that use this must be able to parse their results into this class
case class IamUserInfo(account: Option[IamAccount], sub: String) {   // Note: used to use the email field for ibm cloud, but switched to sub because it is common to both
  def accountId = if (account.isDefined) account.get.bss else ""
}
case class IamAccount(bss: String)

// These error msgs are matched by UsersSuite.scala, so change them there if you change them here
case class OrgNotFound(authInfo: IamAuthCredentials)
  extends UserFacingError(s"IAM authentication succeeded, but no matching exchange org with a cloud account id was found for ${authInfo.org}")
case class IncorrectOrgFound(orgAcctId: String, userInfo: IamUserInfo)
  extends UserFacingError(s"IAM authentication succeeded, but the cloud account id of the org ($orgAcctId) does not match that of the cloud account credentials (${userInfo.accountId})")

/** JAAS module to authenticate to the IBM cloud. Called from AuthenticationSupport:authenticate() because JAAS.config references this module.
  */
class IbmCloudModule extends LoginModule with AuthorizationSupport {
  private var subject: Subject = _
  private var handler: CallbackHandler = _
  private var identity: Identity = _
  private var succeeded = false
  lazy val logger: Logger = LoggerFactory.getLogger(ExchConfig.LOGGER)


  override def initialize(
    subject: Subject,
    handler: CallbackHandler,
    sharedState: java.util.Map[String, _],
    options: java.util.Map[String, _]
  ): Unit = {
    this.subject = subject
    this.handler = handler
  }

  override def login(): Boolean = {
    logger.trace("in IbmCloudModule.login() to try to authenticate an IBM cloud user")
    val reqCallback = new RequestCallback

    handler.handle(Array(reqCallback))
    if (reqCallback.request.isEmpty) {
      logger.error("Unable to get HTTP request while authenticating")
    }

    val loginResult = for {
      reqInfo <- Try(reqCallback.request.get)
      user <- {
        val RequestInfo(req, _, isDbMigration, _, _) = reqInfo
        val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

        for {
          key <- extractApiKey(reqInfo)   // this will bail out of the outer for loop if the user isn't iamapikey or iamapitoken
          username <- IbmCloudAuth.authenticateUser(key)
        } yield {
          val user = IUser(Creds(username, ""))
          logger.info("IBM User " + user.creds.id + " from " + clientIp + " running " + req.getMethod + " " + req.getPathInfo)
          if (isDbMigration && !Role.isSuperUser(user.creds.id)) throw new IsDbMigrationException()
          identity = user
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
        // This looked like an ibm cred, but there was a problem with it, so throw the exception so it gets back to the user
        case e: UserFacingError =>  throw e    // exceptions from verifyOrg(): OrgNotFound, IncorrectOrgFound
        // This was not an ibm cred, so return false so JAAS will move on to the next login module and return any exception from it
        case _: NotIbmCredsException => return false
        //case e: DbTimeoutException => e
        //case e: DbConnectionException => e
        case e: IsDbMigrationException => throw e
        case e: BadIamCombinationException => throw e
        case e: IamApiErrorException => throw e
        //case e: InvalidCredentialsException => e
        //case e: AuthInternalErrorException => e
        //todo: using this instead of the specific Db exceptions above because i haven't figured out yet how to successfully not have Await.result() be the last line of getOrCreateUser().
        case e => throw new DbConnectionException(e.getMessage)
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
      subject.getPrincipals().add(ExchangeRole(identity.role))
    }
    succeeded
  }

  private def extractApiKey(reqInfo: RequestInfo): Try[IamAuthCredentials] = {
    val creds = credentials(reqInfo)
    val (org, id) = IbmCloudAuth.compositeIdSplit(creds.id)
    if ((id == "iamapikey" || id == "iamtoken") && creds.token.nonEmpty) Success(IamAuthCredentials(org, id, creds.token))
    else Failure(new NotIbmCredsException("User is not iamapikey or iamtoken, so credentials are not IBM cloud IAM credentials"))
  }
}

// Utilities for managing the ibm auth cache and authenticating with ibm
object IbmCloudAuth {
  import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private var db: Database = _

  private implicit val formats = DefaultFormats

  lazy val logger: Logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  private val guavaCache = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build[String, Entry[String]]     // the cache key is org/apikey, and the value is org/username
  implicit val userCache = GuavaCache(guavaCache)

  def init(db: Database): Unit = {
    this.db = db
  }

  def authenticateUser(authInfo: IamAuthCredentials): Try[String] = {
    logger.info("authenticateUser(): attempting to authenticate with IBM Cloud with "+authInfo)
    /*
     * The caching library provides several functions that work on
     * the cache defined above. The caching function takes a key and tries
     * to retrieve from the cache, and if it is not there runs the block
     * of code provided, adds the result to the cache, and then returns it.
     * I use cachingF here so that I can return a Try value
     * (see http://cb372.github.io/scalacache/docs/#basic-cache-operations for more info)
     */
    cachingF(authInfo.cacheKey)(ttl = None) {
      for {
        token <- if (authInfo.keyType == "iamtoken") Success(IamToken(authInfo.key))
          else if (isIcp && authInfo.keyType == "iamapikey") Success(IamToken(authInfo.key, Some(authInfo.keyType)))  // this is an apikey we are putting in IamToken, but it can be used like a token in the next step
          else getIamToken(authInfo)
        userInfo <- getUserInfo(token)
        user <- getOrCreateUser(authInfo, userInfo)
      } yield user.username   // this is the composite org/username
    }
  }

  def clearCache(): Try[Unit] = {
    logger.debug(s"Clearing the IBM Cloud auth cache")
    removeAll().map(_ => ())
  }

  private def isIcp = sys.env.get("PLATFORM_IDENTITY_PROVIDER_SERVICE_HOST").nonEmpty   // ICP sets this

  private def getIcpIdentityUrl = "https://" + sys.env.getOrElse("PLATFORM_IDENTITY_PROVIDER_SERVICE_HOST", "") + ":8443"

  // Use the IBM IAM API to authenticate the iamapikey and get an IAM token. See: https://cloud.ibm.com/apidocs/iam-identity-token-api and https://github.ibm.com/IBMPrivateCloud/roadmap/blob/master/feature-specs/security/security-services-apis.md
  private def getIamToken(authInfo: IamAuthCredentials): Try[IamToken] = {
    if (authInfo.keyType == "iamapikey") {
      logger.debug("Retrieving IBM Cloud IAM token using API key")
      val response = Http("https://iam.cloud.ibm.com/identity/token")
        .header("Accept", "application/json")
        .postForm(Seq(
          "grant_type" -> "urn:ibm:params:oauth:grant-type:apikey",
          "apikey" -> authInfo.key
        ))
        .asString
      if (response.code == HttpCode.OK) Try(parse(response.body).camelizeKeys.extract[IamToken])
      else Failure(new IamApiErrorException(response.body.toString))
    } else {
      Failure(new AuthInternalErrorException("the user is not a valid IAM keyword"))
    }
  }

  // Using the IAM token get the ibm cloud account id (which we'll use to verify the exchange org) and users email (which we'll use as the exchange user)
  private def getUserInfo(token: IamToken): Try[IamUserInfo] = {
    if (isIcp && token.tokenType.getOrElse("") == "iamapikey") {
      // An icp platform apikey that we can use directly to authenticate and get the username
      //crl -k -X POST -H 'Content-Type: application/x-www-form-urlencoded' -d "apikey=$ICP_PLATFORM_KEY" $ICP_IAM_URL/iam-token/oidc/introspect
      val iamUrl = getIcpIdentityUrl + "/iam-token/oidc/introspect"
      logger.debug("Retrieving ICP IAM userinfo from " + iamUrl)
      val apiKey = token.accessToken
      //TODO: need to get the self-signed cert so we don't have to use the allowUnsafeSSL option
      val response = Http(iamUrl).method("post").option(HttpOptions.allowUnsafeSSL)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .postData("apikey="+apiKey)
        .asString
      if (response.code == HttpCode.OK) Try(parse(response.body).extract[IamUserInfo])
      else Failure(new IamApiErrorException(response.body.toString))
    } else if (isIcp) {
      // An icp token from the UI
      val iamUrl = getIcpIdentityUrl + "/idprovider/v1/auth/userinfo"
      logger.debug("Retrieving ICP IAM userinfo from " + iamUrl)
      //TODO: need to get the self-signed cert so we don't have to use the allowUnsafeSSL option
      val response = Http(iamUrl).method("post").option(HttpOptions.allowUnsafeSSL)
        .header("Authorization", s"BEARER ${token.accessToken}")
        .header("Content-Type", "application/json")
        .asString
      if (response.code == HttpCode.OK) Try(parse(response.body).extract[IamUserInfo])
      else Failure(new IamApiErrorException(response.body.toString))
    } else {
      // An ibm public cloud token, either from the UI or from the platform apikey we were given
      val iamUrl = "https://iam.cloud.ibm.com/identity/userinfo"
      logger.debug("Retrieving IBM Cloud IAM userinfo from " + iamUrl)
      val response = Http(iamUrl)
        .header("Authorization", s"BEARER ${token.accessToken}")
        .header("Content-Type", "application/json")
        .asString
      if (response.code == HttpCode.OK) Try(parse(response.body).extract[IamUserInfo])
      else Failure(new IamApiErrorException(response.body.toString))
    }
  }

  private def getOrCreateUser(authInfo: IamAuthCredentials, userInfo: IamUserInfo): Try[UserRow] = {
    logger.debug("Getting or creating exchange user from DB using IAM userinfo: "+userInfo)
    // Form a DB query with the right logic to verify the org and either get or create the user.
    // This can throw exceptions OrgNotFound or IncorrectOrgFound
    val userQuery = for {
      //associatedOrgId <- fetchOrg(userInfo) // can no longer use this, because the account id it uses to find the org is not necessarily unique...
      //orgId <- verifyOrg(authInfo, userInfo, associatedOrgId)
      orgAcctId <- fetchOrg(authInfo.org)
      orgId <- verifyOrg(authInfo, userInfo, orgAcctId.flatten) // verify cloud acct id of the apikey and the org entry match
      userRow <- fetchUser(orgId, userInfo)
      userAction <- {
        if (userRow.isEmpty) createUser(orgId, userInfo)
        else DBIO.successful(Success(userRow.get))
      }
    } yield userAction
    //todo: getOrCreateUser() is only called if this is not already in the cache, so its a problem if we cant get it in the db
    //logger.trace("awaiting for DB query of ibm cloud creds for "+authInfo.org+"/"+userInfo.email+"...")
    // Note: exceptions from this get caught in login() above
    Await.result(db.run(userQuery.transactionally), Duration(9000, MILLISECONDS))
    /* it doesnt work to add this to our authorization cache, and causes some exceptions during automated tests
    val awaitResult = Await.result(db.run(userQuery.transactionally), Duration(3000, MILLISECONDS))
    AuthCache.users.putBoth(Creds(s"${authInfo.org}/${userInfo.email}", ""), "")
    awaitResult
    */
  }

  // Get the associated ibm cloud id of the org that the client requested in the exchange api
  private def fetchOrg(org: String) = {
    logger.trace("Fetching org: "+org)
    OrgsTQ.getOrgid(org)
      .map(_.tags.+>>("ibmcloud_id"))
      //.take(1)  // not sure what the purpose of this was
      .result
      .headOption
  }

  // Verify that the cloud acct id of the cloud api key and the exchange org entry match
  // authInfo is the creds they passed in, userInfo is what was returned from the IAM calls, and orgAcctId is what we got from querying the org in the db
  private def verifyOrg(authInfo: IamAuthCredentials, userInfo: IamUserInfo, orgAcctId: Option[String]) = {
    logger.trace("Verifying org: "+authInfo+", "+userInfo+", "+orgAcctId)
    if (userInfo.account.isEmpty) {
      DBIO.successful(authInfo.org)    // this method does not apply to ICP
    } else if (orgAcctId.isEmpty) {
      logger.error(s"IAM authentication succeeded, but no matching exchange org with a cloud account id was found for ${authInfo.org}")
      DBIO.failed(OrgNotFound(authInfo))
    } else if (authInfo.keyType == "iamtoken" && userInfo.accountId == "") {
      // This is the case with tokens from the edge mgmt ui, and this is ok
      DBIO.successful(authInfo.org)
    } else if (orgAcctId.getOrElse("") != userInfo.accountId) {
      logger.error(s"IAM authentication succeeded, but the cloud account id of the org $orgAcctId does not match that of the cloud account ${userInfo.accountId}")
      DBIO.failed(IncorrectOrgFound(orgAcctId.getOrElse(""), userInfo))
    } else {
      DBIO.successful(authInfo.org)
    }
  }

  private def fetchUser(org: String, userInfo: IamUserInfo) = {
    logger.trace("Fetching user: org="+org+", "+userInfo)
    UsersTQ.rows
      .filter(u => u.orgid === org && u.username === s"$org/${userInfo.sub}")
      //.take(1)  // not sure what the purpose of this was
      .result
      .headOption
  }

  private def createUser(org: String, userInfo: IamUserInfo) = {
    logger.trace("Creating user: org="+org+", "+userInfo)
    val user = UserRow(
      s"$org/${userInfo.sub}",
      org,
      "",
      admin = false,
      userInfo.sub,
      ApiTime.nowUTC
    )
    (UsersTQ.rows += user).asTry.map(count => count.map(_ => user))
  }

  // Split an id in the form org/id and return both parts. If there is no / we assume it is an id without the org.
  def compositeIdSplit(compositeId: String): (String, String) = {
    val reg = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org,id) => return (org,id)
      case reg(org,_) => return (org,"")
      case reg(_,id) => return ("",id)
      case _ => return ("", compositeId)
    }
  }
}
