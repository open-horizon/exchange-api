package org.openhorizon.exchangeapi.table.node.message

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

/** The nodemsgs table holds the msgs sent to nodes by agbots */
final case class NodeMsgRow(msgId: Int,
                            nodeId: String,
                            agbotId: String,
                            agbotPubKey: String,
                            message: String,
                            timeSent: String,
                            timeExpires: String) {
  def toNodeMsg: NodeMsg = NodeMsg(msgId, agbotId, agbotPubKey, message, timeSent, timeExpires)

  def insert: DBIO[_] = ((NodeMsgsTQ returning NodeMsgsTQ.map(_.msgId)) += this)  // inserts the row and returns the msgId of the new row
  def upsert: DBIO[_] = NodeMsgsTQ.insertOrUpdate(this)    // do not think we need this
}
