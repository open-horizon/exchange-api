package org.openhorizon.exchangeapi.route.service

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.service.OneProperty
import org.openhorizon.exchangeapi.table.service.policy.ServicePolicyRow
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}

final case class PutServicePolicyRequest(label: Option[String],
                                         description: Option[String],
                                         properties: Option[List[OneProperty]],
                                         constraints: Option[List[String]]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of strings", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        return Option(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
      }
    }
    None
  }

  def toServicePolicyRow(serviceId: String): ServicePolicyRow = ServicePolicyRow(serviceId, label.getOrElse(""), description.getOrElse(label.getOrElse("")), write(properties), write(constraints), ApiTime.nowUTC)
}
