package org.openhorizon.exchangeapi.table.service.policy

import org.openhorizon.exchangeapi.table.service.OneProperty

final case class ServicePolicy(label: String,
                               description: String,
                               properties: List[OneProperty],
                               constraints: List[String],
                               lastUpdated: String)
