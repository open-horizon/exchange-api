package org.openhorizon.exchangeapi.table.node.group.assignment

import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.node.group.NodeGroupRow
import slick.dbio.DBIO

final case class PostPutNodeGroupsRequest(description: Option[String], members: Option[Seq[String]]) {
  def getAnyProblem: Option[String] = None
  
  // Note: write() handles correctly the case where the optional fields are None.
  def getDbUpsertGroup(description: Option[String],
                       group: Long = 0L,
                       lastUpdated: String = ApiTime.nowUTC,
                       name: String,
                       orgid: String): DBIO[_] =
    NodeGroupRow(description = description,
                 group = group,
                 lastUpdated = lastUpdated,
                 name = name,
                 organization = orgid).upsert
}
