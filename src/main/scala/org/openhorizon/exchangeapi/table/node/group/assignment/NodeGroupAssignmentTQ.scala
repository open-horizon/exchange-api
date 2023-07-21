package org.openhorizon.exchangeapi.table.node.group.assignment

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

object NodeGroupAssignmentTQ extends TableQuery(new NodeGroupAssignment(_)){
  def getNodeGroupAssignment(node: String): Query[Rep[Long], Long, Seq] = this.filter(_.node === node).map(_.group)
}
