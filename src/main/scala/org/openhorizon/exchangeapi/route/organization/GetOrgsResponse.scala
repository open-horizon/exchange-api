package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.table.organization.Org

final case class GetOrgsResponse(orgs: Map[String, Org] = Map.empty[String, Org],
                                 lastIndex: Int = 0)
