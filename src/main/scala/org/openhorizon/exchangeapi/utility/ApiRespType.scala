package org.openhorizon.exchangeapi.utility

object ApiRespType {
  val BADCREDS: String = ExchMsg.translate("api.bad.creds")
  val ACCESS_DENIED: String = ExchMsg.translate("api.access.denied")
  val ALREADY_EXISTS: String = ExchMsg.translate("api.already.exists")
  val BAD_INPUT: String = ExchMsg.translate("api.invalid.input")
  val NOT_FOUND: String = ExchMsg.translate("api.not.found")
  val METHOD_NOT_ALLOWED: String = ExchMsg.translate("api.method.not.allowed")
  val INTERNAL_ERROR: String = ExchMsg.translate("api.internal.error")
  val NOT_IMPLEMENTED: String = ExchMsg.translate("api.not.implemented")
  val BAD_GW: String = ExchMsg.translate("api.db.connection.error")
  val GW_TIMEOUT: String = ExchMsg.translate("api.db.timeout")
  val ERROR: String = ExchMsg.translate("error")
  val WARNING: String = ExchMsg.translate("warning")
  val INFO: String = ExchMsg.translate("info")
  val OK: String = ExchMsg.translate("ok")
  val TOO_BUSY: String = ExchMsg.translate("too.busy")
}
