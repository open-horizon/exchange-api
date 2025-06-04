package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.node.Node

/** Output format for GET /orgs/{organization}/nodes */
final case class GetNodesResponse(nodes: Map[String,Node], lastIndex: Int = 0)
