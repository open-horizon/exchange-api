package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreement

/** Output format for GET /orgs/{organization}/nodes/{node}/agreements */
final case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)
