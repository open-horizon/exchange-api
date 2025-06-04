package org.openhorizon.exchangeapi.table.node.status

import org.openhorizon.exchangeapi.table.node.{NodeRow, Nodes, NodesTQ}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ForeignKeyQuery, Index, ProvenShape}

class NodeStatuses(tag: Tag) extends Table[NodeStatusRow](tag, "nodestatus") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def connectivity = column[String]("connectivity")
  def services = column[String]("services")
  def runningServices = column[String]("runningservices")
  def lastUpdated = column[String]("lastUpdated")
  
  def * : ProvenShape[NodeStatusRow] = (nodeId, connectivity, services, runningServices, lastUpdated).<>(NodeStatusRow.tupled, NodeStatusRow.unapply)
  def node: ForeignKeyQuery[Nodes, NodeRow] = foreignKey("node_status_fk_nodes", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_node_status_fk_nodes = index(name = "idx_node_status_fk_nodes", on = nodeId, unique = false)
}
