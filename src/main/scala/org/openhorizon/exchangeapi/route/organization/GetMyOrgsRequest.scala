package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.auth.cloud.IamAccountInfo

final case class GetMyOrgsRequest(accounts: List[IamAccountInfo]) {
  def getAnyProblem: Option[String] = None
}
