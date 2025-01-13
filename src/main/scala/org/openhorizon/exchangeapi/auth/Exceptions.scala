package org.openhorizon.exchangeapi.auth

import akka.http.scaladsl.model.StatusCode
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ExchMsg, HttpCode}

import javax.security.auth.login.LoginException

// Base class for all of the exchange authentication and authorization failures
// See also case class AuthRejection in ApiUtils.scala that can turn any exception into a rejection
//todo: make all of these final case classes
class AuthException(var httpCode: StatusCode, var apiResponse: String, msg: String) extends LoginException(msg) {
  def toComplete = (httpCode, ApiResponse(apiResponse, getMessage))
}

// These error msgs are matched by UsersSuite.scala, so change them there if you change them here
final case class OrgNotFound(authInfoOrg: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("org.not.found.user.facing.error", authInfoOrg))
final case class IncorrectOrgFound(authInfoOrg: String, userInfoAcctId: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("incorrect.org.found.user.facing.error", authInfoOrg, userInfoAcctId))
final case class IncorrectOrgFoundMult(authInfoOrg: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("incorrect.org.found.user.facing.error.mult", authInfoOrg))
final case class IncorrectIcpOrgFound(requestOrg: String, clusterName: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, ExchMsg.translate("incorrect.org.found.user.facing.error.ICP", requestOrg, clusterName))

// Error class to use to define specific error responses from problems happening in DB threads
// Note: this is not strictly an auth error, but it is handy to inherit from AuthException
class DBProcessingError(httpCode: StatusCode, apiResponse: String, msg: String) extends AuthException(httpCode, apiResponse, msg)

// These 2 exceptions will be caught by IbmCloudModule and Module respectively, and return false from login().
// Their http code should never be used, which is why it is an internal error if it unexpectedly is.
// Only used internally: The creds werent ibm cloud creds, so return gracefully and move on to the next login module
class NotIbmCredsException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "not IBM cloud credentials")
// The creds werent local exchange creds, so return gracefully and move on to the next login module
class NotLocalCredsException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "User is iamapikey or iamtoken, so credentials are not local Exchange credentials")
class NotIeamUiCredsException extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, "not IEAM UI credentials")

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

class UserCreateException(msg: String = ExchMsg.translate("error.creating.user.noargs")) extends AuthException(HttpCode.BAD_GW, ApiRespType.BAD_GW, msg)

class AlreadyExistsException(msg: String = ExchMsg.translate("already.exists")) extends AuthException(HttpCode.ALREADY_EXISTS2, ApiRespType.ALREADY_EXISTS, msg)

// Not currently used. The IAM token we were given was expired, or some similar problem
//class BadIamCombinationException(msg: String) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)

// Unexpected http code or response body from an IAM API call
class IamApiErrorException(msg: String) extends AuthException(HttpCode.BAD_GW, ApiRespType.BAD_GW, msg)

// Didn't get a response from an IAM API after a number of retries
class IamApiTimeoutException(msg: String) extends AuthException(HttpCode.GW_TIMEOUT, ApiRespType.GW_TIMEOUT, msg)

// An error occurred while building the SSLSocketFactory with the self-signed cert
class SelfSignedCertException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, msg)

// The creds id was not found in the db
class IdNotFoundException(msg: String = ExchMsg.translate("invalid.credentials")) extends AuthException(HttpCode.BADCREDS, ApiRespType.BADCREDS, msg)

// The id was not found in the db when looking for owner or isPublic
class IdNotFoundForAuthorizationException(msg: String = ExchMsg.translate("access.denied")) extends AuthException(HttpCode.ACCESS_DENIED, ApiRespType.ACCESS_DENIED, msg)

class AuthInternalErrorException(msg: String) extends AuthException(HttpCode.INTERNAL_ERROR, ApiRespType.INTERNAL_ERROR, msg)
