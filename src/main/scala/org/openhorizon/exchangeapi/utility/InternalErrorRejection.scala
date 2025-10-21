package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

final case class InternalErrorRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = StatusCodes.InternalServerError
  
  def apiRespCode: String = ApiRespType.INTERNAL_ERROR
}
