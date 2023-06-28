package org.openhorizon.exchangeapi.route.node

/** Input body for POST /orgs/{orgid}/search/nodes/service -- now in OrgsRoutes */
final case class PostServiceSearchRequest(orgid: String, serviceURL: String, serviceVersion: String, serviceArch: String) {
  require(orgid!=null && serviceURL!=null && serviceVersion!=null && serviceArch!=null)
  def getAnyProblem: Option[String] = None
}
