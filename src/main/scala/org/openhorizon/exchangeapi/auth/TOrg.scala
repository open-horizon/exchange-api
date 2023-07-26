package org.openhorizon.exchangeapi.auth

case class TOrg(id: String) extends Target {
  override def getOrg: String = id    // otherwise the regex in the base class will return blank because there is no /
  override def getId = ""
  override def isThere: Boolean = true   // we don't have a cache to quickly tell if this org exists, so return true and let the db access sort it out
  override def label = "org"
}
