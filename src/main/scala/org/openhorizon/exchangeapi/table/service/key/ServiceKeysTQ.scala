package org.openhorizon.exchangeapi.table.service.key

import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery

object ServiceKeysTQ extends TableQuery(new ServiceKeys(_)) {
  def getKeys(serviceId: String): Query[ServiceKeys, ServiceKeyRow, Seq] = this.filter(_.serviceId === serviceId)
  
  def getKey(serviceId: String, keyId: String): Query[ServiceKeys, ServiceKeyRow, Seq] = this.filter(r => {
    r.serviceId === serviceId && r.keyId === keyId
  })
}
