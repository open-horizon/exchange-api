package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

/** HTTP codes, taken from https://en.wikipedia.org/wiki/List_of_HTTP_status_codes and https://www.restapitutorial.com/httpstatuscodes.html */
object HttpCode {
  /* Now using the pekko StatusCodes instead
  val OK = 200
  val PUT_OK = 201
  val POST_OK = 201
  val DELETED = 204 // technically means no content, but usually used for DELETE
  val BAD_INPUT = 400 // invalid user input, usually in the params or json body
  val BADCREDS = 401 // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED = 403 // do not have authorization to access this resource
  val ALREADY_EXISTS = 403 // trying to create a resource that already exists. For now using 403 (forbidden), but could also use 409 (conflict)
  val ALREADY_EXISTS2 = 409 // trying to create a resource that already exists (409 means conflict)
  val NOT_FOUND = 404 // resource not found
  val INTERNAL_ERROR = 500
  val NOT_IMPLEMENTED = 501
  val BAD_GW = 502 // bad gateway, which for us means db connection error or jetty refused connection
  val GW_TIMEOUT = 504 */
  // gateway timeout, which for us means db timeout
  val OK: StatusCodes.Success = StatusCodes.OK
  val PUT_OK: StatusCodes.Success = StatusCodes.Created
  val POST_OK: StatusCodes.Success = StatusCodes.Created
  val DELETED: StatusCodes.Success = StatusCodes.NoContent // technically means no content, but usually used for DELETE
  val BAD_INPUT: StatusCodes.ClientError = StatusCodes.BadRequest // invalid user input, usually in the params or json body
  val BADCREDS: StatusCodes.ClientError = StatusCodes.Unauthorized // user/pw or id/token is wrong (they call it unauthorized, but it is really unauthenticated)
  val ACCESS_DENIED: StatusCodes.ClientError = StatusCodes.Forbidden // do not have authorization to access this resource
  val ALREADY_EXISTS: StatusCodes.ClientError = StatusCodes.Forbidden // trying to create a resource that already exists. For now using 403 (forbidden), but could also use 409 (conflict)
  val ALREADY_EXISTS2: StatusCodes.ClientError = StatusCodes.Conflict // trying to create a resource that already exists (409 means conflict)
  val NOT_FOUND: StatusCodes.ClientError = StatusCodes.NotFound // resource not found
  val INTERNAL_ERROR: StatusCodes.ServerError = StatusCodes.InternalServerError
  val NOT_IMPLEMENTED: StatusCodes.ServerError = StatusCodes.NotImplemented
  val BAD_GW: StatusCodes.ServerError = StatusCodes.BadGateway // bad gateway, which for us means db connection error or IAM API problem
  val GW_TIMEOUT: StatusCodes.ServerError = StatusCodes.GatewayTimeout // gateway timeout, which for us means db timeout
}
