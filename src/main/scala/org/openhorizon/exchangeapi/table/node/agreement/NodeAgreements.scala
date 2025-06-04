package org.openhorizon.exchangeapi.table.node.agreement

import org.openhorizon.exchangeapi.table.node.NodesTQ
import slick.jdbc.PostgresProfile.api._

class NodeAgreements(tag: Tag) extends Table[NodeAgreementRow](tag, "nodeagreements") {
  def agId = column[String]("agid", O.PrimaryKey)     // agreement ids are unique
  def nodeId = column[String]("nodeid")   // in the form org/nodeid
  def services = column[String]("services")
  def agrSvcOrgid = column[String]("agrsvcorgid")
  def agrSvcPattern = column[String]("agrsvcpattern")
  def agrSvcUrl = column[String]("agrsvcurl")
  def state = column[String]("state")
  def lastUpdated = column[String]("lastUpdated")
 
  def * = (agId, nodeId, services, agrSvcOrgid, agrSvcPattern, agrSvcUrl, state, lastUpdated).<>(NodeAgreementRow.tupled, NodeAgreementRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_node_agree_fk_nodes = index(name = "idx_node_agree_fk_nodes", on = nodeId, unique = false)
}
