package org.openhorizon.exchangeapi.table.node.error

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import slick.jdbc.PostgresProfile.api._

final case class NodeErrorRow(nodeId: String,
                              errors: String,
                              lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toNodeError: NodeError = {
    val err: List[Any] = if (errors != "") read[List[Any]](errors) else List[Any]()
    NodeError(err, lastUpdated)
  }
  
  def upsert = NodeErrorTQ.insertOrUpdate(this)
}
