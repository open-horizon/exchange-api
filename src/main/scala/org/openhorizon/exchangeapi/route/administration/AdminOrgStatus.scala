package org.openhorizon.exchangeapi.route.administration

class AdminOrgStatus(){
  var msg: String = ""
  var nodesByOrg : Map[String, Int] = null
  def toGetAdminOrgStatusResponse: GetAdminOrgStatusResponse = GetAdminOrgStatusResponse(msg, nodesByOrg)
}
