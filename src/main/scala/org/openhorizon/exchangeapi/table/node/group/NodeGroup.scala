package org.openhorizon.exchangeapi.table.node.group

import org.openhorizon.exchangeapi.table.organization.OrgsTQ
import slick.jdbc.PostgresProfile.api._

class NodeGroup(tag: Tag) extends Table[NodeGroupRow](tag, "node_group") {
  def description = column[Option[String]]("description")
  def group = column[Long]("group", O.AutoInc)
  def organization = column[String]("orgid")
  def lastUpdated = column[String]("lastUpdated")
  def name = column[String]("name")
  def admin = column[Boolean]("admin")
  //def lastUpdated = column[String]("lastUpdated")
  def * = (admin, description, group, organization, lastUpdated, name).<>(NodeGroupRow.tupled, NodeGroupRow.unapply)
  def nodeGroupIdx = index("node_group_idx", (organization, name), unique = true)
  def pkNodeGroup = primaryKey("pk_node_group", group)
  def fkOrg = foreignKey("fk_organization", organization, OrgsTQ)(_.orgid, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
