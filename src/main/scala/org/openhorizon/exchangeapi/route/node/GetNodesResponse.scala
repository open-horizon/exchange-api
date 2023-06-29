package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.Node

/** Output format for GET /orgs/{orgid}/nodes */
final case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int)
