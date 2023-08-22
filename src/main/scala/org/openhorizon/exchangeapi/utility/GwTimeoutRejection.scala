package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class GwTimeoutRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = StatusCodes.GatewayTimeout
  
  def apiRespCode: String = ApiRespType.GW_TIMEOUT
}
