package org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._


final case class AgbotPatternRow(patId: String,
                                 agbotId: String,
                                 patternOrgid: String,
                                 pattern: String,
                                 nodeOrgid: String,
                                 lastUpdated: String) {
  def toAgbotPattern: AgbotPattern =
    AgbotPattern(lastUpdated  = lastUpdated,
                 nodeOrgid    = nodeOrgid,
                 pattern      = pattern,
                 patternOrgid = patternOrgid)
  
  def upsert: DBIO[_] = AgbotPatternsTQ.insertOrUpdate(this)
  def insert: DBIO[_] = AgbotPatternsTQ += this
}
