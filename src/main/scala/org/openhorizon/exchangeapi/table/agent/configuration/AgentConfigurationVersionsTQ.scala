package org.openhorizon.exchangeapi.table.agent.configuration

import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import slick.lifted.{Rep, TableQuery}


object AgentConfigurationVersionsTQ extends TableQuery(new AgentConfigurationVersions(_)) {
  def getAgentConfigurationVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.configurationVersion)
}
