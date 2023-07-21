package org.openhorizon.exchangeapi.table.node.message

import org.openhorizon.exchangeapi.table.AgbotsTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import slick.jdbc.PostgresProfile.api._

class NodeMsgs(tag: Tag) extends Table[NodeMsgRow](tag, "nodemsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc)    // this enables them to delete a msg and helps us deliver them in order
  def nodeId = column[String]("nodeid")       // msg recipient
  def agbotId = column[String]("agbotid")         // msg sender
  def agbotPubKey = column[String]("agbotpubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  def * = (msgId, nodeId, agbotId, agbotPubKey, message, timeSent, timeExpires).<>(NodeMsgRow.tupled, NodeMsgRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
