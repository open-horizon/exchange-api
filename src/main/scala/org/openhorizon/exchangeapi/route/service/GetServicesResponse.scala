package org.openhorizon.exchangeapi.route.service

import org.openhorizon.exchangeapi.table.Service

final case class GetServicesResponse(services: Map[String,Service],
                                     lastIndex: Int)
