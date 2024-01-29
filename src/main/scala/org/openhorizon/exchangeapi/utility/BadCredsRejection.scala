package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

//someday: the rest of these rejections are not currently used. Instead the route implementations either do the complete() directly,
//  or turn an AuthException into a complete() using its toComplete method. But maybe it is better for the pekko framework to know it is a rejection.
final case class BadCredsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Unauthorized
  
  def apiRespCode: String = ApiRespType.BADCREDS
}
