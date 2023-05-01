package org.openhorizon.exchangeapi.route.administration

/** Input body for POST /admin/hashpw */
final case class AdminHashpwRequest(password: String) {
  require(password!=null)
}
