package org.openhorizon.exchangeapi.table.agent

final case class AgentVersionsResponse(agentCertVersions: Seq[String],
                                       agentConfigVersions: Seq[String],
                                       agentSoftwareVersions: Seq[String],
                                       lastUpdated: String) extends AgentVersions
