package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class AlreadyExists2Rejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Conflict
  
  def apiRespCode: String = ApiRespType.ALREADY_EXISTS
}
