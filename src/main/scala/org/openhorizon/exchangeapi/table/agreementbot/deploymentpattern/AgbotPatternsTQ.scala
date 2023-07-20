package org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern

import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery


object AgbotPatternsTQ extends TableQuery(new AgbotPatterns(_)) {
  def getPatterns(agbotId: String): Query[AgbotPatterns, AgbotPatternRow, Seq] = this.filter(_.agbotId === agbotId)
  def getPattern(agbotId: String, patId: String): Query[AgbotPatterns, AgbotPatternRow, Seq] = this.filter(r => {r.agbotId === agbotId && r.patId === patId} )
}
