package org.openhorizon.exchangeapi.table.agent

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

import java.time.Instant

object AgentVersionsChangedTQ extends TableQuery(new AgentVersionsChanged(_)) {
  def getChanged(organization: String): Query[Rep[Instant], Instant, Seq] = this.filter(_.organization === organization).map(_.changed)
}
