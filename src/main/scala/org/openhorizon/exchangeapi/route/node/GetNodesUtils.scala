package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.ExchMsg
import org.openhorizon.exchangeapi.table.NodeType

object GetNodesUtils {
  def getNodesProblem(nodetype: Option[String]): Option[String] = {
    if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase)) return Option(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
    None
  }
}
