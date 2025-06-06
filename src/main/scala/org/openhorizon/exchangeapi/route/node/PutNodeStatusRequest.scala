package org.openhorizon.exchangeapi.route.node

import io.swagger.v3.oas.annotations.media.{ArraySchema, Schema}
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.node.OneService
import org.openhorizon.exchangeapi.table.node.status.NodeStatusRow
import org.openhorizon.exchangeapi.utility.ApiTime

/** Input body for PUT /orgs/{organization}/nodes/{node}/status */
final case class PutNodeStatusRequest(connectivity: Option[Map[String,Boolean]], services: List[OneService]) {
  require(connectivity!=null && services!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = None
  var runningServices = "|"
  for(s <- services){
    runningServices = runningServices + s.orgid + "/" + s.serviceUrl + "_" + s.version + "_" + s.arch + "|"
  }
  def toNodeStatusRow(nodeId: String): NodeStatusRow = NodeStatusRow(nodeId, write(connectivity), write(services), runningServices, ApiTime.nowUTC)
}
