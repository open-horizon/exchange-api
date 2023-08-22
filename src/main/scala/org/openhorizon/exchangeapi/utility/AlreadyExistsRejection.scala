package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCodes

final case class AlreadyExistsRejection(apiRespMsg: String) extends ExchangeRejection {
  def httpCode: StatusCodes.ClientError = StatusCodes.Forbidden
  
  def apiRespCode: String = ApiRespType.ALREADY_EXISTS
}
