package org.openhorizon.exchangeapi.table.service.key

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class ServiceKeyRow(keyId: String,
                               serviceId: String,
                               key: String,
                               lastUpdated: String) {
  def toServiceKey: ServiceKey = ServiceKey(key, lastUpdated)
  
  def upsert: DBIO[_] = ServiceKeysTQ.insertOrUpdate(this)
}
