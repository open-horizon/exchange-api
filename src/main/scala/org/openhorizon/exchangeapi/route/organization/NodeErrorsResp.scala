package org.openhorizon.exchangeapi.route.organization

final case class NodeErrorsResp(nodeId: String,
                                error: String,
                                lastUpdated: String)
