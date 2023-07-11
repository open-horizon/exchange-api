package org.openhorizon.exchangeapi.table.node.status

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.OneService
import slick.jdbc.PostgresProfile.api._
import slick.dbio.DBIO

final case class NodeStatusRow(nodeId: String,
                               connectivity: String,
                               services: String,
                               runningServices: String,
                               lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  def toNodeStatus: NodeStatus = {
    val con: Map[String, Boolean] = if (connectivity != "") read[Map[String,Boolean]](connectivity) else Map[String,Boolean]()
    val svc: List[OneService] = if (services != "") read[List[OneService]](services) else List[OneService]()
    NodeStatus(con, svc, runningServices, lastUpdated)
  }

  def upsert: DBIO[_] = NodeStatusTQ.insertOrUpdate(this)
}
