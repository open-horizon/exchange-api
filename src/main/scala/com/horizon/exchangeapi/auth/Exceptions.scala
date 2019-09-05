package com.horizon.exchangeapi.auth

import com.horizon.exchangeapi.{ApiResponseType, ExchangeMessage, HttpCode}
import javax.security.auth.login.{FailedLoginException, LoginException}

//
class AuthException(var httpCode: Int, var apiResponse: String, msg: String) extends LoginException(msg)

// Auth errors we need to report to the user, like the creds looked like an ibm cloud cred, but their org didnt point to a cloud acct
class UserFacingError(msg: String) extends AuthException(HttpCode.BADCREDS, ApiResponseType.BADCREDS, msg)

// The creds werent ibm cloud creds, so return gracefully and move on to the next login module
class NotIbmCredsException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, msg)

// The creds werent local exchange creds, so return gracefully and move on to the next login module
class NotLocalCredsException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, msg)

// We are in the middle of a db migration, so cant authenticate/authorize anything else
class IsDbMigrationException(msg: String = ExchangeMessage.translateMessage("in.process.db.migration")) extends AuthException(HttpCode.ACCESS_DENIED, ApiResponseType.ACCESS_DENIED, msg)

// Exceptions for handling DB connection errors
class DbTimeoutException(msg: String) extends AuthException(HttpCode.GW_TIMEOUT, ApiResponseType.GW_TIMEOUT, msg)
class DbConnectionException(msg: String) extends AuthException(HttpCode.BAD_GW, ApiResponseType.BAD_GW, msg)

class InvalidCredentialsException(msg: String = ExchangeMessage.translateMessage("invalid.credentials")) extends AuthException(HttpCode.BADCREDS, ApiResponseType.BADCREDS, msg)

class UserCreateException(msg: String = ExchangeMessage.translateMessage("error.creating.user.noargs")) extends AuthException(HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, msg)

// The IAM token we were given was expired, or some similar problem
class BadIamCombinationException(msg: String) extends AuthException(HttpCode.BADCREDS, ApiResponseType.BADCREDS, msg)

// The keyword specified was for icp, but not in an icp environment (or vice versa)
class IamApiErrorException(msg: String) extends AuthException(HttpCode.BADCREDS, ApiResponseType.BADCREDS, msg)

// An error occurred while building the SSLSocketFactory with the self-signed cert
class SelfSignedCertException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, msg)

class AuthInternalErrorException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, msg)

object AuthErrors {
  def message(t: Throwable): (Int, String, String) = {
    t match {
      case t: AuthException => (t.httpCode, t.apiResponse, t.getMessage)
      // This is a catch all that probably doesnt get thrown
      case t: FailedLoginException => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      // Should not get here
      case t: Throwable => (HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, t.toString)
      case _ => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, ExchangeMessage.translateMessage("unknown.error.invalid.creds"))
    }
  }
}
