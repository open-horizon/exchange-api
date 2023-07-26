package org.openhorizon.exchangeapi.table.node.message

import org.openhorizon.exchangeapi.utility.ApiTime
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

object NodeMsgsTQ  extends TableQuery(new NodeMsgs(_)){
  def getMsgs(nodeId: String): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(_.nodeId === nodeId)  // this is that nodes msg mailbox
  def getMsg(nodeId: String, msgId: Int): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(r => {r.nodeId === nodeId && r.msgId === msgId} )
  def getMsgsExpired = this.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(nodeId: String): Rep[Int] = this.filter(_.nodeId === nodeId).length
  def getNodeMsgsInOrg(orgid: String): Query[NodeMsgs, NodeMsgRow, Seq] = this.filter(a => {(a.nodeId like orgid + "/%")} )
}
