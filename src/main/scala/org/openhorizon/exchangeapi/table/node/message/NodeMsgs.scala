package org.openhorizon.exchangeapi.table.node.message

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
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
  
  def agbot = foreignKey("node_msg_fk_agbot", agbotId, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def node = foreignKey("node_msg_fk_node", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_node_msg_fk_agbots = index(name = "idx_node_msg_fk_agbots", on = agbotId, unique = false)
  def idx_node_msg_fk_nodes = index(name = "idx_node_msg_fk_nodes", on = nodeId, unique = false)
}
