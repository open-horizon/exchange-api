package org.openhorizon.exchangeapi.route.administration

class AdminStatus() {
  var msg: String = ""
  var numberOfUsers: Int = 0
  var numberOfNodes: Int = 0
  var numberOfNodeAgreements: Int = 0
  var numberOfNodeMsgs: Int = 0
  var numberOfAgbots: Int = 0
  var numberOfAgbotAgreements: Int = 0
  var numberOfAgbotMsgs: Int = 0
  var dbSchemaVersion: Int = 0
  def toGetAdminStatusResponse: GetAdminStatusResponse = GetAdminStatusResponse(msg, numberOfUsers, numberOfNodes, numberOfNodeAgreements, numberOfNodeMsgs, numberOfAgbots, numberOfAgbotAgreements, numberOfAgbotMsgs, dbSchemaVersion)
}
