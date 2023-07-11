package org.openhorizon.exchangeapi.table.node.agreement

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

object NodeAgreementsTQ  extends TableQuery(new NodeAgreements(_)){
  def getAgreements(nodeId: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(_.nodeId === nodeId)
  def getAgreement(nodeId: String, agId: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(r => {r.nodeId === nodeId && r.agId === agId} )
  def getNumOwned(nodeId: String): Rep[Int] = this.filter(_.nodeId === nodeId).length
  def getAgreementsWithState(orgid: String): Query[NodeAgreements, NodeAgreementRow, Seq] = this.filter(a => {(a.nodeId like orgid + "/%") && a.state =!= ""} )
}
