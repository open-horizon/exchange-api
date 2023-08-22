package org.openhorizon.exchangeapi.route.agreementbot

import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.{AgbotBusinessPol, AgbotBusinessPolRow}
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}

/** Input format for POST /orgs/{orgid}/agbots/{id}/businesspols */
final case class PostAgbotBusinessPolRequest(businessPolOrgid: String, businessPol: String, nodeOrgid: Option[String]) {
  require(businessPolOrgid!=null && businessPol!=null)
  def toAgbotBusinessPol: AgbotBusinessPol = AgbotBusinessPol(businessPolOrgid, businessPol, nodeOrgid.getOrElse(businessPolOrgid), ApiTime.nowUTC)
  def toAgbotBusinessPolRow(agbotId: String, busPolId: String): AgbotBusinessPolRow = AgbotBusinessPolRow(busPolId, agbotId, businessPolOrgid, businessPol, nodeOrgid.getOrElse(businessPolOrgid), ApiTime.nowUTC)
  def formId: String = businessPolOrgid + "_" + businessPol + "_" + nodeOrgid.getOrElse(businessPolOrgid)
  def getAnyProblem: Option[String] = {
    val nodeOrg: String = nodeOrgid.getOrElse(businessPolOrgid)
    if (nodeOrg != businessPolOrgid) Option(ExchMsg.translate("node.org.must.equal.bus.pol.org"))
    else None
  }
}
