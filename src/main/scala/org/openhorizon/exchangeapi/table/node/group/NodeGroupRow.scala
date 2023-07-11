package org.openhorizon.exchangeapi.table.node.group

import org.json4s.{DefaultFormats, Formats}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

//Node Groups for MCM
final case class NodeGroupRow(admin: Boolean = false,
                              description: Option[String],
                              group: Long,
                              organization: String,
                              lastUpdated: String,
                              name: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def update: DBIO[_] = (for { m <- NodeGroupTQ if m.group === group } yield m).update(this)
  def upsert: DBIO[_] = NodeGroupTQ.insertOrUpdate(this)
}
