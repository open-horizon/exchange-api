package org.openhorizon.exchangeapi.route.organization

import scala.collection.mutable.ListBuffer

final case class AllNodeErrorsInOrgResp(nodeErrors: ListBuffer[NodeErrorsResp])
