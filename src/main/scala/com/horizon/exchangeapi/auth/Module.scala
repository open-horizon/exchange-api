package com.horizon.exchangeapi.auth

import java.security._

import com.horizon.exchangeapi._
import javax.security.auth._
import javax.security.auth.callback._
import javax.security.auth.spi.LoginModule
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

class Module extends LoginModule with AuthSupport {
  private var subject: Subject = _
  private var handler: CallbackHandler = _
  private var identity: Identity = _
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
    val loginResult = Try {
      handler.handle(Array(reqCallback))
      if (reqCallback.request.isEmpty) {
        throw new Exception("invalid credentials")
      }
      val reqInfo = reqCallback.request.get
      val RequestInfo(req, params, isDbMigration, anonymousOk, hint) = reqInfo
      val clientIp = req.header("X-Forwarded-For").orElse(Option(req.getRemoteAddr)).get // haproxy inserts the real client ip into the header for us

      val feIdentity = frontEndCreds(reqInfo)
      if (feIdentity != null) {
        logger.info("User or id " + feIdentity.creds.id + " from " + clientIp + " (via front end) running " + req.getMethod + " " + req.getPathInfo)
        identity = feIdentity.authenticate()
      } else {
        // Get the creds from the header or params
        val creds = credentials(reqInfo)
        val userOrId = if (creds.isAnonymous) "(anonymous)" else creds.id
        logger.info("User or id " + userOrId + " from " + clientIp + " running " + req.getMethod + " " + req.getPathInfo)
        if (isDbMigration && !Role.isSuperUser(creds.id)) halt(HttpCode.ACCESS_DENIED, ApiResponse(ApiResponseType.ACCESS_DENIED, "access denied - in the process of DB migration"))
        identity = IIdentity(creds).authenticate(hint)
      }
      true
    }
    loginResult.getOrElse(false)
  }

  override def logout(): Boolean = {
    subject.getPrivateCredentials().add(identity)
    true
  }

  override def abort() = false

  override def commit(): Boolean = {
    subject.getPrivateCredentials().add(identity)
    subject.getPrincipals().add(ExchangeRole(identity.role))
    true
  }
}

class ExchCallbackHandler(request: RequestInfo) extends CallbackHandler {
  override def handle(callbacks: Array[Callback]): Unit = {
    for (callback <- callbacks) {
      callback match {
        case cb: RequestCallback => {
          cb.request = request
        }
        case _ =>
      }
    }
  }
}

class RequestCallback extends Callback {
  private var req: Option[RequestInfo] = None

  def request_=(request: RequestInfo): Unit= {
    req = Some(request)
  }

  def request: Option[RequestInfo] = req
}

case class ExchangeRole(role: String) extends Principal {
  override def getName() = role
}

case class AccessPermission(name: String) extends BasicPermission(name)

case class PermissionCheck(permission: String) extends PrivilegedAction[Unit] {
  import Access._

  private val adminNotAllowed = Set(
    CREATE_ORGS.toString,
    READ_OTHER_ORGS.toString,
    WRITE_OTHER_ORGS.toString,
    CREATE_IN_OTHER_ORGS.toString,
    ADMIN.toString
  )

  private def isAdminAllowed(permission: String) = {
    if (adminNotAllowed.contains(permission)) {
      Failure(new Exception(s"Admins are not given the permission $permission"))
    } else {
      Success(())
    }
  }

  override def run() = {
    val literalCheck = Try(AccessController.checkPermission(AccessPermission(permission)))
    lazy val adminCheck =  for {
      allowed <- isAdminAllowed(permission)
      ok <- Try(AccessController.checkPermission(AccessPermission("ALL_IN_ORG")))
    } yield ok
    lazy val superCheck = Try(AccessController.checkPermission(AccessPermission("ALL")))

    for {
      literalFailure <- literalCheck.failed
      _ <- adminCheck.failed
      _ <- superCheck.failed
    } {
      throw literalFailure
    }
  }
}

