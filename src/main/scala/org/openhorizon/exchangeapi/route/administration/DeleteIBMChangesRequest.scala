package org.openhorizon.exchangeapi.route.administration

/** Case class for request body for deleting some of the IBM changes route */
final case class DeleteIBMChangesRequest(resources: List[String]) {
  def getAnyProblem: Option[String] = {
    if (resources.isEmpty) Some("resources list cannot be empty")
    else None
  }
}
