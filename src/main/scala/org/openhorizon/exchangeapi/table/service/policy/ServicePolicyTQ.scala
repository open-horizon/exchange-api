package org.openhorizon.exchangeapi.table.service.policy

import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery


object ServicePolicyTQ extends TableQuery(new ServicePolicies(_)) {
  def getServicePolicy(serviceId: String): Query[ServicePolicies, ServicePolicyRow, Seq] = this.filter(_.serviceId === serviceId)
}
