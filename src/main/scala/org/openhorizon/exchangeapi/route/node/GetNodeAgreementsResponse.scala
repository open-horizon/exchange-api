package org.openhorizon.exchangeapi.route.node

import org.openhorizon.exchangeapi.table.NodeAgreement

/** Output format for GET /orgs/{orgid}/nodes/{id}/agreements */
final case class GetNodeAgreementsResponse(agreements: Map[String,NodeAgreement], lastIndex: Int)
