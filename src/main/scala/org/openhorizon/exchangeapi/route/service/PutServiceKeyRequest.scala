package org.openhorizon.exchangeapi.route.service

import org.openhorizon.exchangeapi.table.service.key.{ServiceKey, ServiceKeyRow}
import org.openhorizon.exchangeapi.utility.ApiTime

final case class PutServiceKeyRequest(key: String) {
  require(key!=null)
  def toServiceKey: ServiceKey = ServiceKey(key, ApiTime.nowUTC)
  def toServiceKeyRow(serviceId: String, keyId: String): ServiceKeyRow = ServiceKeyRow(keyId, serviceId, key, ApiTime.nowUTC)
  def getAnyProblem: Option[String] = None
}
