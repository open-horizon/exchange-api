package org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction


class AgbotBusinessPols(tag: Tag) extends Table[AgbotBusinessPolRow](tag, "agbotbusinesspols") {
  def busPolId = column[String]("buspolid") // key - this is the businessPol's org concatenated with the businessPol name
  def agbotId = column[String]("agbotid") // additional key - the composite orgid/agbotid
  def businessPolOrgid = column[String]("businesspolorgid")
  def businessPol = column[String]("businesspol")
  def nodeOrgid = column[String]("nodeorgid")
  def lastUpdated = column[String]("lastupdated")
  
  def * = (busPolId, agbotId, businessPolOrgid, businessPol, nodeOrgid, lastUpdated).<>(AgbotBusinessPolRow.tupled, AgbotBusinessPolRow.unapply)
  
  def primKey = primaryKey("pk_agbp", (busPolId, agbotId))
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}
