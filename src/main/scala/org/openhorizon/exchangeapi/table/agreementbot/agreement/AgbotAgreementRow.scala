package org.openhorizon.exchangeapi.table.agreementbot.agreement

import org.openhorizon.exchangeapi.table.agreementbot.AAService
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._


final case class AgbotAgreementRow(agrId: String, agbotId: String, serviceOrgid: String, servicePattern: String, serviceUrl: String, state: String, lastUpdated: String, dataLastReceived: String) {
  def toAgbotAgreement: AgbotAgreement = AgbotAgreement(AAService(serviceOrgid, servicePattern, serviceUrl), state, lastUpdated, dataLastReceived)
  
  def upsert: DBIO[_] = AgbotAgreementsTQ.insertOrUpdate(this)
}
