package org.openhorizon.exchangeapi.table.agreementbot.agreement

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}


object AgbotAgreementsTQ extends TableQuery(new AgbotAgreements(_)) {
  def getAgreements(agbotId: String): Query[AgbotAgreements, AgbotAgreementRow, Seq] = this.filter(_.agbotId === agbotId)
  
  def getAgreement(agbotId: String, agrId: String): Query[AgbotAgreements, AgbotAgreementRow, Seq] = this.filter(r => {
    r.agbotId === agbotId && r.agrId === agrId
  })
  
  def getNumOwned(agbotId: String): Rep[Int] = this.filter(_.agbotId === agbotId).length
  
  def getAgreementsWithState = this.filter(_.state =!= "")
}
