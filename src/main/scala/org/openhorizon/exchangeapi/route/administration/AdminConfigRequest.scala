package org.openhorizon.exchangeapi.route.administration

final case class AdminConfigRequest(varPath: String, value: String) {
  require(varPath!=null && value!=null)
}
