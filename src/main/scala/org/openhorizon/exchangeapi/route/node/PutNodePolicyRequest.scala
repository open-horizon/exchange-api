package org.openhorizon.exchangeapi.route.node

import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.{NodePolicyRow, OneProperty, PropertiesAndConstraints}
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg}

final case class PutNodePolicyRequest(label: Option[String], description: Option[String], properties: Option[List[OneProperty]], constraints: Option[List[String]], deployment: Option[PropertiesAndConstraints], management: Option[PropertiesAndConstraints], nodePolicyVersion: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem(noheartbeat: Option[String]): Option[String] = {
    if (noheartbeat.isDefined && noheartbeat.get.toLowerCase != "true" && noheartbeat.get.toLowerCase != "false") return Option(ExchMsg.translate("bad.noheartbeat.param"))
    val validTypes: Set[String] = Set("string", "int", "float", "boolean", "list of strings", "version")
    for (p <- properties.getOrElse(List())) {
      if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
        return Option(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
      }
    }
    if (deployment.isDefined) {
      for (p <- deployment.get.properties.getOrElse(List[OneProperty]())) {
        if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
          return Option(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
        }
      }
    }
    if (management.isDefined) {
      for (p <- management.get.properties.getOrElse(List[OneProperty]())) {
        if (p.`type`.isDefined && !validTypes.contains(p.`type`.get)) {
          return Option(ExchMsg.translate("property.type.must.be", p.`type`.get, validTypes.mkString(", ")))
        }
      }
    }
    None
  }

  def toNodePolicyRow(nodeId: String): NodePolicyRow = NodePolicyRow(nodeId, label.getOrElse(""), description.getOrElse(label.getOrElse("")), write(properties), write(constraints), write(deployment), write(management), nodePolicyVersion.getOrElse(""), ApiTime.nowUTC)
}
