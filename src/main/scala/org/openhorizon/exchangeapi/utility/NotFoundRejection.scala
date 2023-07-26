package org.openhorizon.exchangeapi.utility

import akka.http.scaladsl.model.StatusCodes

final case class NotFoundRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.NotFound
  
  def apiRespCode: String = ApiRespType.NOT_FOUND
}
