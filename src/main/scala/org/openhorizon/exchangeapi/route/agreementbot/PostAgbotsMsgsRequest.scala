package org.openhorizon.exchangeapi.route.agreementbot

/** Input body for POST /orgs/{orgid}/agbots/{id}/msgs */
final case class PostAgbotsMsgsRequest(message: String, ttl: Int) {
  require(message!=null)
}
