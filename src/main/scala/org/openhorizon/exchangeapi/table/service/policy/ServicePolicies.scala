package org.openhorizon.exchangeapi.table.service.policy

import org.openhorizon.exchangeapi.table.service.ServicesTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction


class ServicePolicies(tag: Tag) extends Table[ServicePolicyRow](tag, "servicepolicies") {
  def serviceId = column[String]("serviceid", O.PrimaryKey) // the content of this is orgid/service
  
  def label = column[String]("label")
  
  def description = column[String]("description")
  
  def properties = column[String]("properties")
  
  def constraints = column[String]("constraints")
  
  def lastUpdated = column[String]("lastUpdated")
  
  def * = (serviceId, label, description, properties, constraints, lastUpdated).<>(ServicePolicyRow.tupled, ServicePolicyRow.unapply)
  
  def service = foreignKey("service_fk", serviceId, ServicesTQ)(_.service, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}
