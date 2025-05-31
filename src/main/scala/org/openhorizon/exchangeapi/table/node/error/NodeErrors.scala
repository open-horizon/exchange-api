package org.openhorizon.exchangeapi.table.node.error

import org.openhorizon.exchangeapi.table.node.{NodeRow, Nodes, NodesTQ}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}

class NodeErrors(tag: Tag) extends Table[NodeErrorRow](tag, "nodeerror") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def errors = column[String]("errors")
  def lastUpdated = column[String]("lastUpdated")
  //def * = (nodeId, errors, lastUpdated) <> (NodeErrorRow.tupled, NodeErrorRow.unapply)
  
  def * : ProvenShape[NodeErrorRow] = (nodeId, errors, lastUpdated).mapTo[NodeErrorRow]
  
  def node_error_fk: ForeignKeyQuery[Nodes, NodeRow] = foreignKey("node_error_fk_nodes", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_node_error_fk_nodes = index(name = "idx_node_error_fk_nodes", on = nodeId, unique = false)
}
