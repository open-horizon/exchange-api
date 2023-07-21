package org.openhorizon.exchangeapi.table.node.status

import org.openhorizon.exchangeapi.table.node.NodesTQ
import slick.jdbc.PostgresProfile.api._

class NodeStatuses(tag: Tag) extends Table[NodeStatusRow](tag, "nodestatus") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def connectivity = column[String]("connectivity")
  def services = column[String]("services")
  def runningServices = column[String]("runningservices")
  def lastUpdated = column[String]("lastUpdated")
  def * = (nodeId, connectivity, services, runningServices, lastUpdated).<>(NodeStatusRow.tupled, NodeStatusRow.unapply)
  def node = foreignKey("node_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
}
