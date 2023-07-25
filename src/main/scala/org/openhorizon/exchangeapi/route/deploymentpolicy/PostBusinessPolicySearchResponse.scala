package org.openhorizon.exchangeapi.route.deploymentpolicy

final case class PostBusinessPolicySearchResponse(nodes: List[BusinessPolicyNodeResponse],
                                                  offsetUpdated: Boolean = false)
