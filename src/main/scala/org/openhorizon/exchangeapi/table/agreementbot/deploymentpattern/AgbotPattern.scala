package org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern

final case class AgbotPattern(lastUpdated: String,
                              nodeOrgid: String,
                              pattern: String,
                              patternOrgid: String) {
  // Constructor
  def this(agbotPatternRow: AgbotPatternRow) =
    this(lastUpdated  = agbotPatternRow.lastUpdated,
         nodeOrgid    = agbotPatternRow.nodeOrgid,
         pattern      = agbotPatternRow.pattern,
         patternOrgid = agbotPatternRow.patternOrgid)
}
