package org.openhorizon.exchangeapi.table.node.managementpolicy.status

case class NMPStatus(var agentUpgradePolicyStatus: PolicyStatus){
  def copy = new NMPStatus(agentUpgradePolicyStatus)
}
