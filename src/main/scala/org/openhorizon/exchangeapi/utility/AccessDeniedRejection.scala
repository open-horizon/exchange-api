package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

final case class AccessDeniedRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Forbidden
  
  def apiRespCode: String = ApiRespType.ACCESS_DENIED
}
