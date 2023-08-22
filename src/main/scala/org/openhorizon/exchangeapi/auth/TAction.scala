package org.openhorizon.exchangeapi.auth

case class TAction(id: String = "") extends Target { // for post rest api methods that do not target any specific resource (e.g. admin operations)
  override def label = "action"
}
