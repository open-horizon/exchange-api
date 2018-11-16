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

case class IamAuthCredentials(org: String, apikey: String)
case class IamToken(accessToken: String)
case class IamUserInfo(account: IamAccount, email: String) {
  val accountId = account.bss
}
case class IamAccount(bss: String)

case class OrgNotFound(info: IamUserInfo)
  extends UserFacingError(s"There is no exchange organization for the IBM cloud account with id ${info.accountId}")
case class IncorrectOrgFound(authInfo: IamAuthCredentials, userInfo: IamUserInfo)
  extends UserFacingError(s"A valid IBM Cloud API key was provided, but that cloud account (${userInfo.accountId}) is not associated with org ${authInfo.org}")

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
        val RequestInfo(req, _, isDbMigration, _, hint) = reqInfo
        val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

        for {
          key <- extractApiKey(reqInfo)
          userRow <- IbmCloudAuth.authenticateUser(key)
        } yield {
          val user = IUser(Creds(userRow.username, ""))
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

  private val guavaCache = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build[String, Entry[UserRow]]
  implicit val userCache = GuavaCache(guavaCache)

  def init(db: Database): Unit = {
    this.db = db
  }

  def authenticateUser(authInfo: IamAuthCredentials): Try[UserRow] = {
    logger.info("attempting to authenticate with IBM Cloud")
    /*
     * The caching library provides several functions that work on
     * the cache defined above. The caching function takes a key and tries
     * to retrieve from the cache, and if it is not there runs the block
     * of code provided, adds the result to the cache, and then returns it.
     * I use cachingF here so that I can return a Try value
     * (see http://cb372.github.io/scalacache/docs/#basic-cache-operations for more info)
     */
    cachingF(authInfo.apikey)(ttl = None) {
      for {
        token <- getIamToken(authInfo.apikey)
        userInfo <- getUserInfo(token)
        user <- getOrCreateUser(authInfo, userInfo)
      } yield user
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
      associatedOrgId <- fetchOrg(userInfo)
      orgId <- verifyOrg(authInfo, userInfo, associatedOrgId)
      userRow <- fetchUser(orgId, userInfo)
      user <- {
        if (userRow.isEmpty) createUser(orgId, userInfo)
        else DBIO.successful(Success(userRow.get))
      }
    } yield user
    Await.result(db.run(userQuery.transactionally), Duration(3000, MILLISECONDS))
  }

  private def verifyOrg(authInfo: IamAuthCredentials, userInfo: IamUserInfo, org: Option[String]) = {
    if (org.isEmpty) {
      logger.error(s"IAM authentication succeeded, but no matching org was found for ${userInfo.accountId}")
      DBIO.failed(OrgNotFound(userInfo))
    } else if (org.get != authInfo.org) {
      logger.error(s"IAM authentication succeeded, but the org does not match. User provided org ${authInfo.org} but org ${org.get} matches account ${userInfo.accountId}")
      DBIO.failed(IncorrectOrgFound(authInfo, userInfo))
    } else {
      DBIO.successful(org.get)
    }
  }

  private def fetchOrg(info: IamUserInfo) = {
    OrgsTQ.rows
      .filter(_.tags.+>>("ibmcloud_id") === info.accountId)
      .map(_.orgid)
      .take(1)
      .result
      .headOption
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
      false,
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
