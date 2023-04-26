package org.openhorizon.exchangeapi.route.administration

/** Case class for request body for deleting some of the org changes in the resourcechanges table */
final case class DeleteOrgChangesRequest(resources: List[String]) {
  def getAnyProblem: Option[String] = None
}
