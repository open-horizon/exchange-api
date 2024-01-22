package org.openhorizon.exchangeapi.route.node

/** Input body for POST /orgs/{organization}/nodes/{node}/msgs */
final case class PostNodesMsgsRequest(message: String, ttl: Int) {
  require(message!=null)
}
