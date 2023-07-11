package org.openhorizon.exchangeapi.table.node.agreement

final case class NodeAgreement(services: List[NAService],
                               agrService: NAgrService,
                               state: String,
                               lastUpdated: String)
