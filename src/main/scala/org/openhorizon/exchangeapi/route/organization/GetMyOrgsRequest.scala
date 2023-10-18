package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.auth.IamAccountInfo

final case class GetMyOrgsRequest(accounts: List[IamAccountInfo]) {
  def getAnyProblem: Option[String] = None
}
