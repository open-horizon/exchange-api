package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.AgbotBusinessPol

/** Output format for GET /orgs/{organization}/agbots/{id}/businesspols */
final case class GetAgbotBusinessPolsResponse(businessPols: Map[String,AgbotBusinessPol])
