package org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy

final case class AgbotBusinessPol(businessPolOrgid: String,
                                  businessPol: String,
                                  // deployment_policy: String,
                                  lastUpdated: String,
                                  nodeOrgid: String) {
  // Constructor
  def this(agbotBusinessPolRow: AgbotBusinessPolRow) = {
    this(businessPolOrgid = agbotBusinessPolRow.businessPolOrgid,
         businessPol      = agbotBusinessPolRow.businessPol,
         // deployment_policy = agbotBusinessPolRow.busPolId,
         lastUpdated      = agbotBusinessPolRow.lastUpdated,
         nodeOrgid        = agbotBusinessPolRow.nodeOrgid)
  }
}
