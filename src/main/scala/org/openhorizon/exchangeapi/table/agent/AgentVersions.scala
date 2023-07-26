package org.openhorizon.exchangeapi.table.agent

trait AgentVersions {
  def agentCertVersions: Seq[String]
  def agentConfigVersions: Seq[String]
  def agentSoftwareVersions: Seq[String]
}
