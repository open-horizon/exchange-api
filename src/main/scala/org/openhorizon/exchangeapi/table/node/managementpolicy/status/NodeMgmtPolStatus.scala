package org.openhorizon.exchangeapi.table.node.managementpolicy.status

import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.ManagementPoliciesTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class NodeMgmtPolStatus(tag: Tag) extends Table[NodeMgmtPolStatusRow](tag, "management_policy_status_node") {
  def actualStartTime = column[Option[String]]("time_start_actual")
  def certificateVersion = column[Option[String]]("version_certificate")
  def configurationVersion = column[Option[String]]("version_configuration")
  def endTime = column[Option[String]]("time_end")
  def errorMessage = column[Option[String]]("error_message")
  def node = column[String]("node")
  def policy = column[String]("policy")
  def scheduledStartTime = column[String]("time_start_scheduled")
  def softwareVersion = column[Option[String]]("version_software")
  def status = column[Option[String]]("status")
  def updated = column[String]("updated")
  
  def * = (actualStartTime,
           certificateVersion,
           configurationVersion,
           endTime,
           errorMessage,
           node,
           policy,
           scheduledStartTime,
           softwareVersion,
           status,
           updated).<>(NodeMgmtPolStatusRow.tupled, NodeMgmtPolStatusRow.unapply)
  def pkNodeMgmtPolStatus = primaryKey("pk_management_policy_status_node", (node, policy))
  
  def fkNode = foreignKey("fk_node", node, NodesTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkManagementPolicy = foreignKey("fk_management_policy", policy, ManagementPoliciesTQ)(_.managementPolicy, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
}
