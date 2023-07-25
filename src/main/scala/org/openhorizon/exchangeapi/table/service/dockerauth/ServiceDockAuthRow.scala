package org.openhorizon.exchangeapi.table.service.dockerauth

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class ServiceDockAuthRow(dockAuthId: Int, serviceId: String, registry: String, username: String, token: String, lastUpdated: String) {
  def toServiceDockAuth: ServiceDockAuth = ServiceDockAuth(dockAuthId, registry, username, token, lastUpdated)
  
  // The returning operator is necessary on insert to have it return the id auto-generated, instead of the number of rows inserted
  def insert: DBIO[_] = (ServiceDockAuthsTQ returning ServiceDockAuthsTQ.map(_.dockAuthId)) += this
  
  def update: DBIO[_] = ServiceDockAuthsTQ.getDockAuth(serviceId, dockAuthId).update(this)
}
