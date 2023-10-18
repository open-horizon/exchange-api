package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.Agbot

/** Output format for GET /orgs/{organization}/agbots */
final case class GetAgbotsResponse(agbots: Map[String,Agbot], lastIndex: Int)
