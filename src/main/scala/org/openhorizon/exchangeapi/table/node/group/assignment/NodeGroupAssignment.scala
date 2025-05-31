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
  def fkGroup = foreignKey("node_grp_assgn_fk_node_grps", group, NodeGroupTQ)(_.group, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkNode = foreignKey("node_grp_assgn_fk_nodes", node, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_node_grp_assgn_fk_node = index(name = "idx_node_grp_assgn_fk_node", on = node, unique = false)
  def idx_node_grp_assgn_fk_node_grps = index(name = "idx_node_grp_assgn_fk_node_grps", on = group, unique = false)
}
