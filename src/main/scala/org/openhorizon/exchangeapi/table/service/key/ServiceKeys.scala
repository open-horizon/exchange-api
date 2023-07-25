package org.openhorizon.exchangeapi.table.service.key

import org.openhorizon.exchangeapi.table.service.ServicesTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class ServiceKeys(tag: Tag) extends Table[ServiceKeyRow](tag, "servicekeys") {
  def keyId = column[String]("keyid") // key - the key name
  def serviceId = column[String]("serviceid") // additional key - the composite orgid/serviceid
  def key = column[String]("key") // the actual key content
  def lastUpdated = column[String]("lastupdated")
  
  def * = (keyId, serviceId, key, lastUpdated).<>(ServiceKeyRow.tupled, ServiceKeyRow.unapply)
  
  def primKey = primaryKey("pk_svck", (keyId, serviceId))
  def service = foreignKey("service_fk", serviceId, ServicesTQ)(_.service, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}
