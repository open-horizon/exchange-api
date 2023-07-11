package org.openhorizon.exchangeapi.table.node.error

import org.openhorizon.exchangeapi.table.node.NodesTQ
import slick.jdbc.PostgresProfile.api._

class NodeErrors(tag: Tag) extends Table[NodeErrorRow](tag, "nodeerror") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def errors = column[String]("errors")
  def lastUpdated = column[String]("lastUpdated")
  //def * = (nodeId, errors, lastUpdated) <> (NodeErrorRow.tupled, NodeErrorRow.unapply)
  def * = (nodeId, errors, lastUpdated).mapTo[NodeErrorRow]
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}
