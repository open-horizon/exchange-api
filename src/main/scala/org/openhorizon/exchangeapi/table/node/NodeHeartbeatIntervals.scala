package org.openhorizon.exchangeapi.table.node

final case class NodeHeartbeatIntervals(minInterval: Int,
                                        maxInterval: Int,
                                        intervalAdjustment: Int)
