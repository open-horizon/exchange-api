package org.openhorizon.exchangeapi.table.node.status

import org.openhorizon.exchangeapi.table.node.OneService

final case class NodeStatus(connectivity: Map[String,Boolean],
                            services: List[OneService],
                            runningServices: String,
                            lastUpdated: String)
