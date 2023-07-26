package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class BadGwRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = StatusCodes.BadGateway
  
  def apiRespCode: String = ApiRespType.BAD_GW
}
