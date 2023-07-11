package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.node.message.NodeMsg

/** Response for GET /orgs/{orgid}/nodes/{id}/msgs */
final case class GetNodeMsgsResponse(messages: List[NodeMsg], lastIndex: Int)
