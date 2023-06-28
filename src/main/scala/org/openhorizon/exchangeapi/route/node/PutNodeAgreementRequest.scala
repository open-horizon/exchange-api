package org.openhorizon.exchangeapi.route.node

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.{NAService, NAgrService, NodeAgreementRow}
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg}

/** Input format for PUT /orgs/{orgid}/nodes/{id}/agreements/<agreement-id> */
final case class PutNodeAgreementRequest(services: Option[List[NAService]], agreementService: Option[NAgrService], state: String) {
  require(state!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(noheartbeat: Option[String]): Option[String] = {
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Option(ExchMsg.translate("bad.noheartbeat.param"))
    if (services.isEmpty && agreementService.isEmpty) {
      return Option(ExchMsg.translate("must.specify.service.or.agreementservice"))
    }
    None
  }

  def toNodeAgreementRow(nodeId: String, agId: String): NodeAgreementRow = {
    if (agreementService.isDefined) NodeAgreementRow(agId, nodeId, write(services), agreementService.get.orgid, agreementService.get.pattern, agreementService.get.url, state, ApiTime.nowUTC)
    else NodeAgreementRow(agId, nodeId, write(services), "", "", "", state, ApiTime.nowUTC)
  }
}
