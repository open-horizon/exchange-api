package org.openhorizon.exchangeapi.route.service

import org.openhorizon.exchangeapi.table.node.NodeType
import org.openhorizon.exchangeapi.utility.{ExchMsg, Version}

object GetServicesUtils {
  def getServicesProblem(public: Option[String], version: Option[String], nodetype: Option[String]): Option[String] = {
    if (public.isDefined && !(public.get.toLowerCase == "true" || public.get.toLowerCase == "false")) return Option(ExchMsg.translate("bad.public.param"))
    if (version.isDefined && !Version(version.get).isValid) return Option(ExchMsg.translate("version.not.valid.format", version.get))
    if (nodetype.isDefined && !NodeType.containsString(nodetype.get.toLowerCase)) return Option(ExchMsg.translate("invalid.node.type2", NodeType.valuesAsString))
    None
  }
}
