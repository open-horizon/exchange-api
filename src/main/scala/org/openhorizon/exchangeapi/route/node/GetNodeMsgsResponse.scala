package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.node.message.NodeMsg

/** Response for GET /orgs/{organization}/nodes/{node}/msgs */
final case class GetNodeMsgsResponse(messages: List[NodeMsg], lastIndex: Int)
