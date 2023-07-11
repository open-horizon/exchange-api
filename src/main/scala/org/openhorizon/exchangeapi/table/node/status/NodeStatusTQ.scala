package org.openhorizon.exchangeapi.table.node.status

import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery

object NodeStatusTQ extends TableQuery(new NodeStatuses(_)) {
  def getNodeStatus(nodeId: String): Query[NodeStatuses, NodeStatusRow, Seq] = this.filter(_.nodeId === nodeId)
}
