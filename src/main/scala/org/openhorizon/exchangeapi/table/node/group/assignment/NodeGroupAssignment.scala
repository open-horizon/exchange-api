package org.openhorizon.exchangeapi.table.node.group.assignment

import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class NodeGroupAssignment(tag: Tag) extends Table[NodeGroupAssignmentRow](tag, "node_group_assignment") {
  def node = column[String]("node")
  def group = column[Long]("group")
  def * = (node, group).<>(NodeGroupAssignmentRow.tupled, NodeGroupAssignmentRow.unapply)
  def pkNodeGroup = primaryKey("pk_node_group_assignment", node)
  def fkGroup = foreignKey("fk_group", group, NodeGroupTQ)(_.group, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkNode = foreignKey("fk_node", node, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
