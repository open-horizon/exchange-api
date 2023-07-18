package org.openhorizon.exchangeapi.route.managementpolicy

import org.openhorizon.exchangeapi.table.ManagementPolicy

final case class GetManagementPoliciesResponse(managementPolicy: Map[String, ManagementPolicy],
                                               lastIndex: Int)
