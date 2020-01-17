package com.horizon.exchangeapi.auth

import akka.http.scaladsl.model.StatusCode
import com.horizon.exchangeapi.{ApiRespType, ApiResponse, ExchMsg, HttpCode}
import javax.security.auth.login.{FailedLoginException, LoginException}

// Base class for all of the exchange authentication and authorization failures
//todo: make all of these final case classes
class AuthException(var httpCode: StatusCode, var apiResponse: String, msg: String) extends LoginException(msg) {
  def toComplete = (httpCode, ApiResponse(apiResponse, getMessage))
}

// These error msgs are matched by UsersSuite.scala, so change them there if you change them here
case class OrgNotFound(authInfo: IamAuthCredentials) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("org.not.found.user.facing.error", authInfo.org))
case class IncorrectOrgFound(orgAcctId: String, userInfo: IamUserInfo) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("incorrect.org.found.user.facing.error", orgAcctId, userInfo.accountId))
case class IncorrectIcpOrgFound(requestOrg: String, clusterName: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("incorrect.org.found.user.facing.error.ICP", requestOrg, clusterName))

// Error class to use to define specific error responses from problems happening in DB threads
// Note: this is not strictly an auth error, but it is handy to inherit from AuthException
class DBProcessingError(httpCode: StatusCode, apiResponse: String, msg: String) extends AuthException(httpCode, apiResponse, msg)

// Only used internally: The creds werent ibm cloud creds, so return gracefully and move on to the next login module
class NotIbmCredsException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "not IBM cloud credentials")

// The creds werent local exchange creds, so return gracefully and move on to the next login module
class NotLocalCredsException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "User is iamapikey or iamtoken, so credentials are not local Exchange credentials")

// We are in the middle of a db migration, so cant authenticate/authorize anything else
class IsDbMigrationException(msg: String = ExchMsg.translate("in.process.db.migration")) extends AuthException(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, msg)

// Exceptions for handling DB connection errors
class DbTimeoutException(msg: String) extends AuthException(HttpCode.GW_TIMEOUT, ApiRespType.GW_TIMEOUT, msg)
class DbConnectionException(msg: String) extends AuthException(HttpCode.BAD_GW, ApiRespType.BAD_GW, msg)

class InvalidCredentialsException(msg: String = ExchMsg.translate("invalid.credentials")) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)

class OrgNotSpecifiedException(msg: String = ExchMsg.translate("org.not.specified")) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)

class AccessDeniedException(msg: String = ExchMsg.translate("access.denied")) extends AuthException(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, msg)

class BadInputException(msg: String = ExchMsg.translate("bad.input")) extends AuthException(HttpCode.BAD_INPUT, ApiRespType.BAD_INPUT, msg)

class ResourceNotFoundException(msg: String = ExchMsg.translate("not.found")) extends AuthException(HttpCode.NOT_FOUND, ApiRespType.NOT_FOUND, msg)

class UserCreateException(msg: String = ExchMsg.translate("error.creating.user.noargs")) extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, msg)

// The IAM token we were given was expired, or some similar problem
class BadIamCombinationException(msg: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)

// The keyword specified was for icp, but not in an icp environment (or vice versa)
class IamApiErrorException(msg: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)


// An error occurred while building the SSLSocketFactory with the self-signed cert
class SelfSignedCertException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, msg)

// Only used internally: The local exchange id was not found in the db
class IdNotFoundException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "id not found")

class AuthInternalErrorException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, msg)

object AuthErrors {
  def message(t: Throwable): (StatusCode, String, String) = {
    t match {
      case t: AuthException => (t.httpCode, t.apiResponse, t.getMessage)
      // This is a catch all that probably doesnt get thrown
      case t: FailedLoginException => (HttpCode.BADCREDS, ApiRespType.BADCREDS, t.getMessage)
      // Should not get here
      case t: Throwable => (HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, t.toString)
      case _ => (HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("unknown.error.invalid.creds"))
    }
  }
}
