package org.openhorizon.exchangeapi.utility

import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.server.Rejection
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization

trait ExchangeRejection extends Rejection {
  private implicit val formats: DefaultFormats.type = DefaultFormats
  
  def httpCode: StatusCode
  
  def apiRespCode: String
  
  def apiRespMsg: String
  
  def toApiResp: ApiResponse = ApiResponse(apiRespCode, apiRespMsg)
  
  def toJsonStr: String = Serialization.write(ApiResponse(apiRespCode, apiRespMsg))
  
  override def toString: String = s"Rejection http code: $httpCode, message: $apiRespMsg"
}
