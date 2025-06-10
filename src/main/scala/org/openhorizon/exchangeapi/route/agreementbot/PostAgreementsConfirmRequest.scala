package org.openhorizon.exchangeapi.route.agreementbot

/** Input body for POST /orgs/{organization}/agreements/confirm */
final case class PostAgreementsConfirmRequest(agreementId: String) {
  require(agreementId != null)
}
