package com.horizon.exchangeapi.auth

import java.util.concurrent.TimeUnit

import com.google.common.cache.CacheBuilder
import com.horizon.exchangeapi._
import com.horizon.exchangeapi.tables.{OrgsTQ, UserRow, UsersTQ}
import javax.security.auth._
import javax.security.auth.callback._
import javax.security.auth.login.FailedLoginException
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

case class IamAuthCredentials(org: String, apikey: String) {
  override def toString = org + "/" + apikey
}
case class IamToken(accessToken: String)
case class IamUserInfo(account: IamAccount, email: String) {
  val accountId = account.bss
}
case class IamAccount(bss: String)

// These error msgs are matched by UsersSuite.scala, so change them there if you change them here
case class OrgNotFound(authInfo: IamAuthCredentials)
  extends UserFacingError(s"IAM authentication succeeded, but no matching org with a cloud account id was found for ${authInfo.org}")
case class IncorrectOrgFound(orgAcctId: String, userInfo: IamUserInfo)
  extends UserFacingError(s"IAM authentication succeeded, but the cloud account id of the org ($orgAcctId) does not match that of the cloud account credentials (${userInfo.accountId})")

/** JAAS module to authenticate to the IBM cloud. Called from AuthenticationSupport:authenticate() because jaas.config references this module.
  */
class IbmCloudModule extends LoginModule with AuthSupport {
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
          key <- extractApiKey(reqInfo)
          username <- IbmCloudAuth.authenticateUser(key)
        } yield {
          val user = IUser(Creds(username, ""))
          logger.info("User " + user.creds.id + " from " + clientIp + " running " + req.getMethod + " " + req.getPathInfo)
          if (isDbMigration && !Role.isSuperUser(user.creds.id)) halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied - in the process of DB migration"))
          identity = user
          user
        }
      }
    } yield user
    succeeded = loginResult.isSuccess
    if (!succeeded) {
      throw loginResult.failed.map {
        case e: UserFacingError => e
        case _ => new FailedLoginException
      }.get
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
    val (org, id) = IbmCloudAuth.compositIdSplit(creds.id)
    //todo: we should verify the org is the same as the org they are trying to access
    if (id == "iamapikey" && !creds.token.isEmpty) Success(IamAuthCredentials(org, creds.token))
    else Failure(new Exception("Auth is not an IAM apikey"))
  }
}

object IbmCloudAuth {
  import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private var db: Database = _

  private implicit val formats = DefaultFormats

  lazy val logger: Logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

  //case class CacheEntry(org: String, baseUsername: String)  // not used

  private val guavaCache = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build[String, Entry[String]]     // the cache key is org/apiky, and the value is org/username
  implicit val userCache = GuavaCache(guavaCache)

  def init(db: Database): Unit = {
    this.db = db
  }

  //def authenticateUser(authInfo: IamAuthCredentials): Try[UserRow] = {
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
    cachingF(authInfo.toString)(ttl = None) {
      for {
        token <- getIamToken(authInfo.apikey)
        userInfo <- getUserInfo(token)
        user <- getOrCreateUser(authInfo, userInfo)
      } yield user.username   // this is the composite org/username
    }
  }

  def clearCache(): Try[Unit] = {
    logger.debug(s"Clearing the IBM Cloud auth cache")
    removeAll().map(_ => ())
  }

  private def getIamToken(apikey: String): Try[IamToken] = {
    logger.debug("Retrieving IAM token")
    val tokenResponse = Http("https://iam.cloud.ibm.com/identity/token")
      .header("Accept", "application/json")
      .postForm(Seq(
        "grant_type" -> "urn:ibm:params:oauth:grant-type:apikey",
        "apikey" -> apikey
      ))
      .asString
    Try(parse(tokenResponse.body).camelizeKeys.extract[IamToken])
  }

  private def getUserInfo(token: IamToken): Try[IamUserInfo] = {
    logger.debug("Retrieving IAM userinfo")
    val infoResponse = Http("https://iam.cloud.ibm.com/identity/userinfo")
      .header("Authorization", s"BEARER ${token.accessToken}")
      .header("Content-Type", "application/json")
      .asString
    Try(parse(infoResponse.body).extract[IamUserInfo])
  }

  private def getOrCreateUser(authInfo: IamAuthCredentials, userInfo: IamUserInfo): Try[UserRow] = {
    val userQuery = for {
      //associatedOrgId <- fetchOrg(userInfo) // can no longer use this, because the account id it uses to find the org is not necessarily unique...
      //orgId <- verifyOrg(authInfo, userInfo, associatedOrgId)
      ibmCloudAcctId <- fetchOrg(authInfo.org)
      orgId <- verifyOrg(authInfo, userInfo, ibmCloudAcctId)   // verify cloud acct id of the apikey and the org entry match
      userRow <- fetchUser(orgId, userInfo)
      user <- {
        if (userRow.isEmpty) createUser(orgId, userInfo)
        else DBIO.successful(Success(userRow.get))
      }
    } yield user
    //todo: can this just be a future? getOrCreateUser() is only called if this is not already in the cache, so its a problem if we cant get it in the db
    Await.result(db.run(userQuery.transactionally), Duration(3000, MILLISECONDS))
    /* it doesnt work to add this to our authorization cache, and cause some exceptions during automated tests
    val awaitResult = Await.result(db.run(userQuery.transactionally), Duration(3000, MILLISECONDS))
    AuthCache.users.putBoth(Creds(s"${authInfo.org}/${userInfo.email}", ""), "")
    awaitResult
    */
  }

  // Verify that the cloud acct id of the cloud api key and the exchange org entry match
  private def verifyOrg(authInfo: IamAuthCredentials, userInfo: IamUserInfo, orgAcctId: Option[String]) = {
    if (orgAcctId.isEmpty) {
      logger.error(s"IAM authentication succeeded, but no matching org with a cloud account id was found for ${authInfo.org}")
      DBIO.failed(OrgNotFound(authInfo))
    } else if (orgAcctId.getOrElse("") != userInfo.accountId) {
      logger.error(s"IAM authentication succeeded, but the cloud account id of the org $orgAcctId does not match that of the cloud account ${userInfo.accountId}")
      DBIO.failed(IncorrectOrgFound(orgAcctId.getOrElse(""), userInfo))
    } else {
      DBIO.successful(authInfo.org)
    }
  }

  /* This fetch is no longer valid because we no longer require the ibmcloud_id field to be unique, so this could find any of the orgs with this ibmcloud_id.
  private def fetchOrg(info: IamUserInfo) = {
    OrgsTQ.rows
      .filter(_.tags.+>>("ibmcloud_id") === info.accountId)
      .map(_.orgid)
      .take(1)
      .result
      .headOption
  }
  */

  // Get the associated ibm cloud id of the org that the client requested in the exchange api
  private def fetchOrg(org: String) = {
    OrgsTQ.getOrgid(org)
      .map(_.tags.+>>("ibmcloud_id"))
      .take(1)
      .result
      .head
  }

  private def fetchUser(org: String, info: IamUserInfo) = {
    UsersTQ.rows
      .filter(u => u.orgid === org && u.email === info.email)
      .take(1)
      .result
      .headOption
  }

  private def createUser(org: String, info: IamUserInfo) = {
    val user = UserRow(
      s"$org/${info.email}",
      org,
      "",
      admin = false,
      info.email,
      ApiTime.nowUTC
    )
    (UsersTQ.rows += user).asTry.map(count => count.map(_ => user))
  }

  // Split an id in the form org/id and return both parts. If there is no / we assume it is an id without the org.
  def compositIdSplit(compositeId: String): (String, String) = {
    val reg = """^(\S*?)/(\S*)$""".r
    compositeId match {
      case reg(org,id) => return (org,id)
      case reg(org,_) => return (org,"")
      case reg(_,id) => return ("",id)
      case _ => return ("", compositeId)
    }
  }
}
