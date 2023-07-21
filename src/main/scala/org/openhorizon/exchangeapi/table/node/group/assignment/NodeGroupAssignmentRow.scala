package org.openhorizon.exchangeapi.table.node.group.assignment

import org.json4s.{DefaultFormats, Formats}
import slick.jdbc.PostgresProfile.api._

final case class NodeGroupAssignmentRow(node: String,
                                        group: Long) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def upsert = NodeGroupAssignmentTQ.insertOrUpdate(this)
}
