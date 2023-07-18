package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.agreementbot.{AgbotPattern, AgbotPatternRow}

/** Input format for POST /orgs/{orgid}/agbots/{id}/patterns */
final case class PostAgbotPatternRequest(patternOrgid: String, pattern: String, nodeOrgid: Option[String]) {
  require(patternOrgid!=null && pattern!=null)
  def toAgbotPattern: AgbotPattern = AgbotPattern(patternOrgid, pattern, nodeOrgid.getOrElse(patternOrgid), ApiTime.nowUTC)
  def toAgbotPatternRow(agbotId: String, patId: String): AgbotPatternRow = AgbotPatternRow(patId, agbotId, patternOrgid, pattern, nodeOrgid.getOrElse(patternOrgid), ApiTime.nowUTC)
  def formId: String = patternOrgid + "_" + pattern + "_" + nodeOrgid.getOrElse(patternOrgid)
  def getAnyProblem: Option[String] = None
}
