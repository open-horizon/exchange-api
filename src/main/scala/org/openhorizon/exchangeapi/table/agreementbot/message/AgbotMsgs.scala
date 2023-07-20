package org.openhorizon.exchangeapi.table.agreementbot.message

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class AgbotMsgs(tag: Tag) extends Table[AgbotMsgRow](tag, "agbotmsgs") {
  def msgId = column[Int]("msgid", O.PrimaryKey, O.AutoInc) // this enables them to delete a msg and helps us deliver them in order
  def agbotId = column[String]("agbotid") // msg recipient
  def nodeId = column[String]("nodeid") // msg sender
  def nodePubKey = column[String]("nodepubkey")
  def message = column[String]("message")
  def timeSent = column[String]("timesent")
  def timeExpires = column[String]("timeexpires")
  
  def * = (msgId, agbotId, nodeId, nodePubKey, message, timeSent, timeExpires).<>(AgbotMsgRow.tupled, AgbotMsgRow.unapply)
  
  def agbot = foreignKey("agbot_fk", agbotId, AgbotsTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade) // Can't keep a node foreign key because if a node is deleted during un-registration, the agbot needs to have a chance to process the agreement cancellation msg and with the node foreign key the message is deleted when the node is deleted
  // def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
