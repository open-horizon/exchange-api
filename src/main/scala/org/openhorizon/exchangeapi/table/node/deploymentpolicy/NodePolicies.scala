package org.openhorizon.exchangeapi.table.node.deploymentpolicy

import org.openhorizon.exchangeapi.table.node.NodesTQ
import slick.jdbc.PostgresProfile.api._

class NodePolicies(tag: Tag) extends Table[NodePolicyRow](tag, "nodepolicies") {
  def nodeId = column[String]("nodeid", O.PrimaryKey)
  def label = column[String]("label")
  def description = column[String]("description")
  def properties = column[String]("properties")
  def constraints = column[String]("constraints")
  def deployment = column[String]("deployment")
  def management = column[String]("management")
  def nodePolicyVersion = column[String]("nodepolicyversion")
  def lastUpdated = column[String]("lastUpdated")
  
  def * = (nodeId, label, description, properties, constraints, deployment, management, nodePolicyVersion, lastUpdated).<>(NodePolicyRow.tupled, NodePolicyRow.unapply)
  
  def node = foreignKey("node_deploy_pol_fk", nodeId, NodesTQ)(_.id, onUpdate = ForeignKeyAction.Cascade, onDelete = ForeignKeyAction.Cascade)
  def idx_node_deploy_pol_fk_nodes = index(name = "idx_node_deploy_pol_fk_nodes", on = nodeId, unique = false)
}
