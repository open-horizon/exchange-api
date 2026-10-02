package org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy

import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import slick.lifted.TableQuery


object AgbotBusinessPolsTQ extends TableQuery(new AgbotBusinessPols(_)) {
  def getBusinessPols(agbotId: String): Query[AgbotBusinessPols, AgbotBusinessPolRow, Seq] = this.filter(_.agbotId === agbotId)
  
  def getBusinessPol(agbotId: String, busPolId: String): Query[AgbotBusinessPols, AgbotBusinessPolRow, Seq] = this.filter(r => {
    r.agbotId === agbotId && r.busPolId === busPolId
  })
}
