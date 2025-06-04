package org.openhorizon.exchangeapi.table.service.dockerauth

import org.openhorizon.exchangeapi.table.service.ServicesTQ
import slick.jdbc.PostgresProfile.api._


class ServiceDockAuths(tag: Tag) extends Table[ServiceDockAuthRow](tag, "servicedockauths") {
  def dockAuthId = column[Int]("dockauthid", O.PrimaryKey, O.AutoInc) // dockAuth - the generated id for this resource
  def serviceId = column[String]("serviceid") // additional key - the composite orgid/serviceid
  def registry = column[String]("registry") // the docker registry this token is for
  def username = column[String]("username") // the type of token, usually 'token' or 'iamapikey'
  def token = column[String]("token") // the actual token content
  def lastUpdated = column[String]("lastupdated")
  
  def * = (dockAuthId, serviceId, registry, username, token, lastUpdated).<>(ServiceDockAuthRow.tupled, ServiceDockAuthRow.unapply)
  
  //def primKey = primaryKey("pk_svck", (dockAuthId, serviceId))    // <- the auto-created id is already unique
  def service = foreignKey("service_fk", serviceId, ServicesTQ)(_.service, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_serv_dock_auth_fk_services = index(name = "idx_serv_dock_auth_fk_services", on = serviceId, unique = false)
}
