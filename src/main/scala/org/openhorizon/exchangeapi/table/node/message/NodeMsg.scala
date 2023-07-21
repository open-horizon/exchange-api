package org.openhorizon.exchangeapi.table.node.message

final case class NodeMsg(msgId: Int,
                         agbotId: String,
                         agbotPubKey: String,
                         message: String,
                         timeSent: String,
                         timeExpires: String)
