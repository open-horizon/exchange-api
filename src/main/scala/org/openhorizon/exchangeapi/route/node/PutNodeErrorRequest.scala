package org.openhorizon.exchangeapi.route.node

import io.swagger.v3.oas.annotations.media.{ArraySchema, Schema}
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.error.NodeErrorRow
import org.openhorizon.exchangeapi.utility.ApiTime

/** Input body for PUT /orgs/{organization}/nodes/{node}/errors */
final case class PutNodeErrorRequest(@ArraySchema(schema = new Schema(implementation = classOf[Object])) errors: List[Any]) {
  require(errors!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None

  def toNodeErrorRow(nodeId: String): NodeErrorRow = NodeErrorRow(nodeId, write(errors), ApiTime.nowUTC)
}
