package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.AgbotAgreement

/** Output format for GET /orgs/{orgid}/agbots/{id}/agreements */
final case class GetAgbotAgreementsResponse(agreements: Map[String,AgbotAgreement], lastIndex: Int)
