package org.openhorizon.exchangeapi.route.organization

class OrgStatus() {
  var msg: String = ""
  var numberOfUsers: Int = 0
  var numberOfNodes: Int = 0
  var numberOfNodeAgreements: Int = 0
  var numberOfRegisteredNodes: Int = 0
  var numberOfNodeMsgs: Int = 0
  var dbSchemaVersion: Int = 0
  def toGetOrgStatusResponse: GetOrgStatusResponse = GetOrgStatusResponse(msg, numberOfUsers, numberOfNodes, numberOfNodeAgreements,numberOfRegisteredNodes, numberOfNodeMsgs, dbSchemaVersion)
}
