package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.AgbotPattern

/** Output format for GET /orgs/{orgid}/agbots/{id}/patterns */
final case class GetAgbotPatternsResponse(patterns: Map[String,AgbotPattern])
