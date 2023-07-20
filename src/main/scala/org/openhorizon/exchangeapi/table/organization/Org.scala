package org.openhorizon.exchangeapi.table.organization

import org.openhorizon.exchangeapi.table.node.NodeHeartbeatIntervals


final case class Org(orgType: String,
                     label: String,
                     description: String,
                     lastUpdated: String,
                     tags: Option[Map[String, String]],
                     limits: OrgLimits,
                     heartbeatIntervals: NodeHeartbeatIntervals)
