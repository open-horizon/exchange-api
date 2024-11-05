package org.openhorizon.exchangeapi.route.administration

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include

@JsonInclude(Include.NON_ABSENT) // Hides key/value pairs that are None.
case class AdminStatus(dbSchemaVersion: Option[Int] = Option(-1),
                       msg: String = "",
                       numberOfAgbotAgreements: Int = 0,
                       numberOfAgbotMsgs: Int = 0,
                       numberOfAgbots: Int = 0,
                       numberOfNodeAgreements: Option[Int] = Option(-1),
                       numberOfNodeMsgs: Option[Int] = Option(-1),
                       numberOfNodes: Option[Int] = Option(-1),
                       numberOfOrganizations: Int = 0,
                       numberOfRegisteredNodes: Option[Int] = None,
                       numberOfUnregisteredNodes: Option[Int] = None,
                       numberOfUsers: Option[Int] = Option(-1),
                       SchemaVersion: Option[Int] = Option(-1)) {
  def this(DBMetrics: (Int, Int, Int, Int, Int, Int, Option[Int], Option[Int], Int, Int, Int), message: String, organization: Boolean) =
    this(dbSchemaVersion = if(!organization) Option(DBMetrics._11) else None,
         msg = message,
         numberOfAgbotAgreements = DBMetrics._1,
         numberOfAgbotMsgs = DBMetrics._2,
         numberOfAgbots = DBMetrics._3,
         numberOfNodeAgreements = Option(DBMetrics._4),
         numberOfNodeMsgs = Option(DBMetrics._5),
         numberOfNodes = Option(DBMetrics._6),
         numberOfOrganizations = DBMetrics._9,
         numberOfRegisteredNodes = DBMetrics._7,
         numberOfUnregisteredNodes = DBMetrics._8,
         numberOfUsers = Option(DBMetrics._10),
         SchemaVersion = if(organization) Option(DBMetrics._11) else None)
}
