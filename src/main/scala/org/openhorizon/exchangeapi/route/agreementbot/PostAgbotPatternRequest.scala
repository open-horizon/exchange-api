package org.openhorizon.exchangeapi.route.agreementbot

/** Input format for POST /orgs/{organization}/agbots/{id}/patterns */
final case class PostAgbotPatternRequest(patternOrgid: String, pattern: String, nodeOrgid: Option[String]) {
  require(patternOrgid!=null && pattern!=null)
}
