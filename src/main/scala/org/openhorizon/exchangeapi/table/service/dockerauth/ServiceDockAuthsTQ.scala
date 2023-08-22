package org.openhorizon.exchangeapi.table.service.dockerauth

import org.openhorizon.exchangeapi.utility.ApiTime
import slick.dbio.{Effect, NoStream}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery
import slick.sql.FixedSqlAction

object ServiceDockAuthsTQ extends TableQuery(new ServiceDockAuths(_)) {
  def getDockAuths(serviceId: String): Query[ServiceDockAuths, ServiceDockAuthRow, Seq] = this.filter(_.serviceId === serviceId)
  
  def getDockAuth(serviceId: String, dockAuthId: Int): Query[ServiceDockAuths, ServiceDockAuthRow, Seq] = this.filter(r => {
    r.serviceId === serviceId && r.dockAuthId === dockAuthId
  })
  
  def getDupDockAuth(serviceId: String, registry: String, username: String, token: String): Query[ServiceDockAuths, ServiceDockAuthRow, Seq] = this.filter(r => {
    r.serviceId === serviceId && r.registry === registry && r.username === username && r.token === token
  })
  
  def getLastUpdatedAction(serviceId: String, dockAuthId: Int): FixedSqlAction[Int, NoStream, Effect.Write] = this.filter(r => {
    r.serviceId === serviceId && r.dockAuthId === dockAuthId
  }).map(_.lastUpdated).update(ApiTime.nowUTC)
}
