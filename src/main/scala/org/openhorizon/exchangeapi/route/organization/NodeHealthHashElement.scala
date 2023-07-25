package org.openhorizon.exchangeapi.route.organization

class NodeHealthHashElement(var lastHeartbeat: Option[String],
                            var agreements: Map[String, NodeHealthAgreementElement])
