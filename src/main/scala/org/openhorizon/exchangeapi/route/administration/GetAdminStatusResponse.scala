package org.openhorizon.exchangeapi.route.administration

final case class GetAdminStatusResponse(msg: String, numberOfUsers: Int, numberOfNodes: Int, numberOfNodeAgreements: Int, numberOfNodeMsgs: Int, numberOfAgbots: Int, numberOfAgbotAgreements: Int, numberOfAgbotMsgs: Int, dbSchemaVersion: Int)
