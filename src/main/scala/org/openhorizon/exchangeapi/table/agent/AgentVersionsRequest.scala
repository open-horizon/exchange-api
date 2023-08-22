package org.openhorizon.exchangeapi.table.agent

final case class AgentVersionsRequest(agentCertVersions: Seq[String],
                                      agentConfigVersions: Seq[String],
                                      agentSoftwareVersions: Seq[String]) extends AgentVersions
