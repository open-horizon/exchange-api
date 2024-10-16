package org.openhorizon.exchangeapi.route.administration

case class AdminStatus(dbSchemaVersion: Int = 0,
                       msg: String = "",
                       numberOfAgbotAgreements: Int = 0,
                       numberOfAgbotMsgs: Int = 0,
                       numberOfAgbots: Int = 0,
                       numberOfNodeAgreements: Int = 0,
                       numberOfNodeMsgs: Int = 0,
                       numberOfNodes: Int = 0,
                       numberOfOrganizations: Int = 0,
                       numberOfRegisteredNodes: Int = 0,
                       numberOfUnregisteredNodes: Int = 0,
                       numberOfUsers: Int = 0,
                       SchemaVersion: Int = 0) {
  def this(DBMetrics: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int), message: String) =
    this(dbSchemaVersion = DBMetrics._11,
         msg = message,
         numberOfAgbotAgreements = DBMetrics._1,
         numberOfAgbotMsgs = DBMetrics._2,
         numberOfAgbots = DBMetrics._3,
         numberOfNodeAgreements = DBMetrics._4,
         numberOfNodeMsgs = DBMetrics._5,
         numberOfNodes = DBMetrics._6,
         numberOfOrganizations = DBMetrics._9,
         numberOfRegisteredNodes = DBMetrics._7,
         numberOfUnregisteredNodes = DBMetrics._8,
         numberOfUsers = DBMetrics._10,
         SchemaVersion = DBMetrics._11)
}
