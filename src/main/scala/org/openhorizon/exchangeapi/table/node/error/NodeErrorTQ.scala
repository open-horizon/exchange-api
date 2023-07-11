package org.openhorizon.exchangeapi.table.node.error

import slick.jdbc.PostgresProfile.api._

object NodeErrorTQ extends TableQuery(new NodeErrors(_)) {
  def getNodeError(nodeId: String) = this.filter(_.nodeId === nodeId)
}
