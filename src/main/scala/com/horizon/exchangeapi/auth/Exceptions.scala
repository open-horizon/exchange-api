package com.horizon.exchangeapi.auth

import com.horizon.exchangeapi.{ApiResponseType, HttpCode}
import javax.security.auth.login.{FailedLoginException, LoginException}

// Auth errors we need to report to the user, like the creds looked like an ibm cloud cred, but their org didnt point to a cloud acct
class UserFacingError(msg: String) extends LoginException(msg)

// The creds werent ibm cloud creds, so return gracefully and move on to the next login module
class NotIbmCredsException(msg: String) extends LoginException(msg)

// The creds werent local exchange creds, so return gracefully and move on to the next login module
class NotLocalCredsException(msg: String) extends LoginException(msg)

// We are in the middle of a db migration, so cant authenticate/authorize anything else
class IsDbMigrationException(msg: String = "access denied - in the process of DB migration") extends LoginException(msg)

// Exceptions for handling DB connection errors
class DbTimeoutException(msg: String) extends LoginException(msg)
class DbConnectionException(msg: String) extends LoginException(msg)

class InvalidCredentialsException(msg: String = "invalid credentials") extends LoginException(msg)

// The IAM token we were given was expired, or some similar problem
class BadIamCombinationException(msg: String) extends LoginException(msg)

// The keyword specified was for icp, but not in an icp environment (for vice versa)
class IamApiErrorException(msg: String) extends LoginException(msg)

class AuthInternalErrorException(msg: String) extends LoginException(msg)

object AuthErrors {
  def message(t: Throwable): (Int, String, String) = {
    t match {
      case t: DbTimeoutException => (HttpCode.GW_TIMEOUT, ApiResponseType.GW_TIMEOUT, t.getMessage)
      case t: DbConnectionException => (HttpCode.GW_TIMEOUT, ApiResponseType.GW_TIMEOUT, t.getMessage)
      case t: UserFacingError => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      case t: InvalidCredentialsException => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      case t: BadIamCombinationException => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      case t: IamApiErrorException => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      // This one shouldnt ever get this far
      case t: NotIbmCredsException => (HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, t.getMessage)
      // This one shouldnt ever get this far
      case t: NotLocalCredsException => (HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, t.getMessage)
      // This one shouldnt ever get this far
      case t: IsDbMigrationException => (HttpCode.ACCESS_DENIED, ApiResponseType.ACCESS_DENIED, t.getMessage)
      // This is a catch all that probably doesnt get thrown
      case t: FailedLoginException => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, t.getMessage)
      // Should not get here
      case t: AuthInternalErrorException => (HttpCode.INTERNAL_ERROR, ApiResponseType.INTERNAL_ERROR, t.getMessage)
      case t: Throwable => (HttpCode.INTERNAL_ERROR, ApiResponseType.ERROR, t.toString)
      case _ => (HttpCode.BADCREDS, ApiResponseType.BADCREDS, "unknown error or invalid credentials")
    }
  }
}
