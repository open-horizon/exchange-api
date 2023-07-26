package org.openhorizon.exchangeapi.table.agreementbot.message

import org.openhorizon.exchangeapi.utility.ApiTime
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}


object AgbotMsgsTQ extends TableQuery(new AgbotMsgs(_)) {
  def getMsgs(agbotId: String): Query[AgbotMsgs, AgbotMsgRow, Seq] = this.filter(_.agbotId === agbotId) // this is that agbots msg mailbox
  def getMsg(agbotId: String, msgId: Int): Query[AgbotMsgs, AgbotMsgRow, Seq] = this.filter(r => {
    r.agbotId === agbotId && r.msgId === msgId
  })
  def getMsgsExpired = this.filter(_.timeExpires < ApiTime.nowUTC)
  def getNumOwned(agbotId: String): Rep[Int] = this.filter(_.agbotId === agbotId).length
}
