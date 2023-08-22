package org.openhorizon.exchangeapi.table.agent

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}

import java.sql.Timestamp

object AgentVersionsChangedTQ extends TableQuery(new AgentVersionsChanged(_)) {
  def getChanged(organization: String): Query[Rep[Timestamp], Timestamp, Seq] = this.filter(_.organization === organization).map(_.changed)
}
