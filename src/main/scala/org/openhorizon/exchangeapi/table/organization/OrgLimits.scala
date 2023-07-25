package org.openhorizon.exchangeapi.table.organization

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}

final case class OrgLimits(maxNodes: Int)

object OrgLimits {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def toOrgLimit(limitsString: String): OrgLimits = if (limitsString != "") read[OrgLimits](limitsString) else OrgLimits(0)
}