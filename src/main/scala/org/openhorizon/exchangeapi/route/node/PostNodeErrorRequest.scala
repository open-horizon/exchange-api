package org.openhorizon.exchangeapi.route.node

// Leaving this here for the UI wanting to implement filtering later
final case class PostNodeErrorRequest() {
  def getAnyProblem: Option[String] = None
}
