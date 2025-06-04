package org.openhorizon.exchangeapi.route.deploymentpattern

import org.openhorizon.exchangeapi.table.deploymentpattern.Pattern

final case class GetPatternsResponse(patterns: Map[String,Pattern] = Map.empty[String, Pattern],
                                     lastIndex: Int = 0)
