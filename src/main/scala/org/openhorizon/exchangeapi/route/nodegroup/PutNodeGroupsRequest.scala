package org.openhorizon.exchangeapi.route.nodegroup

final case class PutNodeGroupsRequest(members: Option[Seq[String]],
                                      description: Option[String]) {
  def getAnyProblem: Option[String] = None
}
