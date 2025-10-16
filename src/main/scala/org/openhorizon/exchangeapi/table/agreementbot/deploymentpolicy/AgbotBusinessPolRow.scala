package org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class AgbotBusinessPolRow(busPolId: String,
                                     agbotId: String,
                                     businessPolOrgid: String,
                                     businessPol: String,
                                     nodeOrgid: String,
                                     lastUpdated: String) {
  def toAgbotBusinessPol: AgbotBusinessPol =
    AgbotBusinessPol(businessPolOrgid = businessPolOrgid,
                     businessPol      = businessPol,
                     lastUpdated      = lastUpdated,
                     nodeOrgid        = nodeOrgid)
  
  def upsert: DBIO[_] = AgbotBusinessPolsTQ.insertOrUpdate(this)
  
  def insert: DBIO[_] = AgbotBusinessPolsTQ += this
}
