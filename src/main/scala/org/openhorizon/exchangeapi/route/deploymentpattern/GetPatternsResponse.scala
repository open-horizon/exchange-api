package org.openhorizon.exchangeapi.route.deploymentpattern

import org.openhorizon.exchangeapi.table.Pattern

final case class GetPatternsResponse(patterns: Map[String,Pattern],
                                     lastIndex: Int)
