package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class BadInputRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.BadRequest
  
  def apiRespCode: String = ApiRespType.BAD_INPUT
}
