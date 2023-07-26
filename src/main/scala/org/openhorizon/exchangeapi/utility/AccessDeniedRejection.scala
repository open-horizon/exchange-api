package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class AccessDeniedRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Forbidden
  
  def apiRespCode: String = ApiRespType.ACCESS_DENIED
}
