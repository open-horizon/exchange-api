package org.openhorizon.exchangeapi.table.deploymentpolicy.search

final case class SearchOffsetPolicyAttributes(agbot: String,
                                              offset: Option[String] = None,
                                              policy: String,
                                              session: Option[String] = None)
