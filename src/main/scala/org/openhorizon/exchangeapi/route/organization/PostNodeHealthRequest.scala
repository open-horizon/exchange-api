package org.openhorizon.exchangeapi.route.organization

final case class PostNodeHealthRequest(lastTime: String,
                                       nodeOrgids: Option[List[String]]) {
  require(lastTime!=null)
  def getAnyProblem: Option[String] = None
}
