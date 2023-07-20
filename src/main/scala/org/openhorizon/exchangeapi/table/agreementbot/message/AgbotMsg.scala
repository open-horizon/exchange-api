package org.openhorizon.exchangeapi.table.agreementbot.message

final case class AgbotMsg(msgId: Int,
                          nodeId: String,
                          nodePubKey: String,
                          message: String,
                          timeSent: String,
                          timeExpires: String)
