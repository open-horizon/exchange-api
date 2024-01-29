package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.openhorizon.exchangeapi.auth.AuthException

// Converts an exception into an auth rejection
final case class AuthRejection(t: Throwable) extends ExchangeRejection {
  //todo: if a generic Throwable is passed in, maybe use something other than invalid creds
  def httpCode: StatusCode = t match {
    case e: AuthException => e.httpCode
    case _ => StatusCodes.Unauthorized // should never get here
  }
  
  def apiRespCode: String = t match {
    case e: AuthException => e.apiResponse
    case _ => "invalid-credentials" // should never get here
  }
  
  def apiRespMsg: String = t match {
    case e: AuthException => e.getMessage
    case _ => "invalid credentials" // should never get here
  }
}
