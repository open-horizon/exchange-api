package org.openhorizon.exchangeapi.table.node.agreement

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.agreement
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class NodeAgreementRow(agId: String,
                                  nodeId: String,
                                  services: String,
                                  agrSvcOrgid: String,
                                  agrSvcPattern: String,
                                  agrSvcUrl: String,
                                  state: String,
                                  lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  // Translates the MS string into a data structure
  def getServices: List[NAService] = if (services != "") read[List[NAService]](services) else List[NAService]()
  def getNAgrService: NAgrService = NAgrService(agrSvcOrgid, agrSvcPattern, agrSvcUrl)
  
  def toNodeAgreement: NodeAgreement = {
    agreement.NodeAgreement(getServices, getNAgrService, state, lastUpdated)
  }

  def upsert: DBIO[_] = NodeAgreementsTQ.insertOrUpdate(this)
}
