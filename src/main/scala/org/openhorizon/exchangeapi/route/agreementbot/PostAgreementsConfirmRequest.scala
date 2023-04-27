package org.openhorizon.exchangeapi.route.agreementbot

/** Input body for POST /orgs/{orgid}/agreements/confirm */
final case class PostAgreementsConfirmRequest(agreementId: String) {
  require(agreementId!=null)
}
