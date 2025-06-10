package org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction


class AgbotPatterns(tag: Tag) extends Table[AgbotPatternRow](tag, "agbotpatterns") {
  def patId = column[String]("patid") // key - this is the pattern's org concatenated with the pattern name
  def agbotId = column[String]("agbotid") // additional key - the composite orgid/agbotid
  def patternOrgid = column[String]("patternorgid")
  def pattern = column[String]("pattern")
  def nodeOrgid = column[String]("nodeorgid")
  def lastUpdated = column[String]("lastupdated")
  
  def * = (patId, agbotId, patternOrgid, pattern, nodeOrgid, lastUpdated).<>(AgbotPatternRow.tupled, AgbotPatternRow.unapply)
  
  def primKey = primaryKey("pk_agp", (patId, agbotId))
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_agbot_pattern_fk_agbots = index(name = "idx_agbot_pattern_fk_agbots", on = agbotId, unique = false)
}
