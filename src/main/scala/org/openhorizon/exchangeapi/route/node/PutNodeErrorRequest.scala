package org.openhorizon.exchangeapi.route.node

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.ApiTime
import org.openhorizon.exchangeapi.table.node.error.NodeErrorRow

/** Input body for PUT /orgs/{orgid}/nodes/{id}/errors */
final case class PutNodeErrorRequest(errors: List[Any]) {
  require(errors!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None

  def toNodeErrorRow(nodeId: String): NodeErrorRow = NodeErrorRow(nodeId, write(errors), ApiTime.nowUTC)
}
