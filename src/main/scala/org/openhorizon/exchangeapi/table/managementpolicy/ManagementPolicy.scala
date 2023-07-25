package org.openhorizon.exchangeapi.table.managementpolicy

import org.openhorizon.exchangeapi.table.service.OneProperty


class ManagementPolicy(var agentUpgradePolicy: AgentUpgradePolicy,
                       var constraints: List[String],
                       var created: String,
                       var description: String,
                       var enabled: Boolean,
                       var label: String,
                       var lastUpdated: String,
                       var owner: String,
                       var patterns: List[String],
                       var properties: List[OneProperty],
                       var start: String = "now",
                       var startWindow: Long = 0) {
  override def clone = new ManagementPolicy(agentUpgradePolicy.clone(), constraints, created, description, enabled, label, lastUpdated, owner, patterns, properties, start, startWindow)
  
  def copy: ManagementPolicy = clone()
}
