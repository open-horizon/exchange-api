package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.agreement.AgbotAgreement

/** Output format for GET /orgs/{organization}/agbots/{id}/agreements */
final case class GetAgbotAgreementsResponse(agreements: Map[String,AgbotAgreement], lastIndex: Int)
