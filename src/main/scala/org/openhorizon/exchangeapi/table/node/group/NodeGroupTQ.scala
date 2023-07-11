package org.openhorizon.exchangeapi.table.node.group

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

object NodeGroupTQ extends TableQuery(new NodeGroup(_)){
  def getAllNodeGroups(orgid: String): Query[NodeGroup, NodeGroupRow, Seq] = this.filter(_.organization === orgid)
  def getNodeGroupId(orgid: String, name: String): Query[Rep[Long], Long, Seq] = this.filter(_.organization === orgid).filter(_.name === name).map(_.group)
  def getNodeGroupName(orgid: String, name: String): Query[NodeGroup, NodeGroupRow, Seq] = this.filter(_.organization === orgid).filter(_.name === name)
}
