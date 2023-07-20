package org.openhorizon.exchangeapi.table.agreementbot.message

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._


/** The agbotmsgs table holds the msgs sent to agbots by nodes */
final case class AgbotMsgRow(msgId: Int, agbotId: String, nodeId: String, nodePubKey: String, message: String, timeSent: String, timeExpires: String) {
  def toAgbotMsg: AgbotMsg = AgbotMsg(msgId, nodeId, nodePubKey, message, timeSent, timeExpires)
  
  def insert: DBIO[_] = ((AgbotMsgsTQ returning AgbotMsgsTQ.map(_.msgId)) += this) // inserts the row and returns the msgId of the new row
  
  def upsert: DBIO[_] = AgbotMsgsTQ.insertOrUpdate(this) // do not think we need this
}
