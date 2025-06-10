package org.openhorizon.exchangeapi.table.agreementbot.agreement

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class AgbotAgreements(tag: Tag) extends Table[AgbotAgreementRow](tag, "agbotagreements") {
  def agrId = column[String]("agrid", O.PrimaryKey) // agreeement ids are unique
  def agbotId = column[String]("agbotid")
  def serviceOrgid = column[String]("serviceorgid")
  def servicePattern = column[String]("servicepattern")
  def serviceUrl = column[String]("serviceurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
  def dataLastReceived = column[String]("dataLastReceived")
  
  def * = (agrId, agbotId, serviceOrgid, servicePattern, serviceUrl, state, lastUpdated, dataLastReceived).<>(AgbotAgreementRow.tupled, AgbotAgreementRow.unapply)
  
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_agbot_agree_fk_agbots = index(name = "idx_agbot_agree_fk_agbots", on = agbotId, unique = false)
}
