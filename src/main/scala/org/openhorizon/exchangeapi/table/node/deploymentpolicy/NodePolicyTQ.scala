package org.openhorizon.exchangeapi.table.node.deploymentpolicy

import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery

object NodePolicyTQ extends TableQuery(new NodePolicies(_)) {
  def getNodePolicy(nodeId: String): Query[NodePolicies, NodePolicyRow, Seq] = this.filter(_.nodeId === nodeId)
}
