package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

final case class InternalErrorRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ServerError = HttpCode.INTERNAL_ERROR
  
  def apiRespCode: String = ApiRespType.INTERNAL_ERROR
}
