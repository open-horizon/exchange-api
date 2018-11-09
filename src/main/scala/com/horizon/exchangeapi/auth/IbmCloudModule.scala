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

case class IamToken(accessToken: String)
case class IamUserInfo(account: IamAccount, email: String) {
  val accountId = account.bss
}
case class IamAccount(bss: String)

class IbmCloudModule extends LoginModule with AuthSupport {
  private var subject: Subject = _
  private var handler: CallbackHandler = _
  private var identity: Identity = _
  private var succeeded = false
  lazy val logger: Logger = LoggerFactory.getLogger("IbmCloudModule")


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
    val loginResult = Try {
      handler.handle(Array(reqCallback))
      if (reqCallback.request.isEmpty) {
        logger.debug("Unable to get HTTP request while authenticating")
        throw new Exception("invalid credentials")
      }
      val reqInfo = reqCallback.request.get
      val RequestInfo(req, _, isDbMigration, _, hint) = reqInfo
      val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

      logger.info("attempting to authenticate with IBM Cloud")
      var authenticated = false
      for {
        key <- extractApiKey(reqInfo)
        userRow <- IbmCloudAuth.authenticateUser(key)
      } {
        val user = IUser(Creds(userRow.username, ""))
        logger.info("User " + user.creds.id + " from " + clientIp + " running " + req.getMethod + " " + req.getPathInfo)
        if (isDbMigration && !Role.isSuperUser(user.creds.id)) halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied - in the process of DB migration"))
        identity = user
        authenticated = true
      }
      authenticated
    }
    succeeded = loginResult.getOrElse(false)
    if (!succeeded) throw new FailedLoginException()
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

  private def extractApiKey(reqInfo: RequestInfo): Try[String] = {
    val creds = credentials(reqInfo)
    if (creds.id == "iamapikey" && !creds.token.isEmpty) Success(creds.token)
    else Failure(new Exception("Auth is not an IAM apikey"))
  }
}

object IbmCloudAuth {
  import com.horizon.exchangeapi.tables.ExchangePostgresProfile.api._

  import scala.concurrent.ExecutionContext.Implicits.global

  private var db: Database = _

  private implicit val formats = DefaultFormats

  private val guavaCache = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build[String, Entry[UserRow]]
  implicit private val userCache = GuavaCache(guavaCache)

  def init(db: Database): Unit = {
    this.db = db
  }

  def authenticateUser(apikey: String): Try[UserRow] = {
    cachingF(apikey)(ttl = None) {
      for {
        token <- getIamToken(apikey)
        info <- getUserInfo(token)
        user <- getOrCreateUser(info)
      } yield user
    }
  }

  private def getIamToken(apikey: String): Try[IamToken] = {
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
    val infoResponse = Http("https://iam.cloud.ibm.com/identity/userinfo")
      .header("Authorization", s"BEARER ${token.accessToken}")
      .header("Content-Type", "application/json")
      .asString
    Try(parse(infoResponse.body).extract[IamUserInfo])
  }

  private def getOrCreateUser(info: IamUserInfo): Try[UserRow] = {
    val userQuery = for {
      orgId <- fetchOrg(info)
      userRow <- fetchUser(orgId, info)
      user <-
        if (userRow.isEmpty) {
          createUser(orgId, info)
        } else {
          DBIO.successful(Success(userRow.get))
        }
    } yield user
    Await.result(db.run(userQuery.transactionally), Duration(3000, MILLISECONDS))
  }

  private def fetchOrg(info: IamUserInfo) = {
    OrgsTQ.rows
      .filter(_.tags.+>>("ibmcloud_id") === info.accountId)
      .map(_.orgid)
      .take(1)
      .result
      .headOption
  }

  private def fetchUser(org: Option[String], info: IamUserInfo) = {
    if (org.isEmpty) DBIO.failed(new Exception("Org not found"))
    else UsersTQ.rows
      .filter(u => u.orgid === org.get && u.email === info.email)
      .take(1)
      .result
      .headOption
  }

  private def createUser(orgId: Option[String], info: IamUserInfo) = {
    if (orgId.isEmpty) DBIO.failed(new Exception("Org not found"))
    else {
      val org = orgId.get
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
  }
}
