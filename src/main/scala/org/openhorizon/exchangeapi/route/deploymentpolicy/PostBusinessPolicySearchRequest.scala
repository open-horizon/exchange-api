package org.openhorizon.exchangeapi.route.deploymentpolicy

final case class PostBusinessPolicySearchRequest(changedSince: Long = 0L,
                                                 nodeOrgids: Option[List[String]] = None,
                                                 numEntries: Option[Int] = None,
                                                 session: Option[String] = None,
                                                 startIndex: Option[String] = None)
