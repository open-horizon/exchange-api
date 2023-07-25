package org.openhorizon.exchangeapi.table.managementpolicy

final case class AgentUpgradePolicy(allowDowngrade: Boolean,
                                    manifest: String) {
  override def clone = new AgentUpgradePolicy(allowDowngrade, manifest)
}
