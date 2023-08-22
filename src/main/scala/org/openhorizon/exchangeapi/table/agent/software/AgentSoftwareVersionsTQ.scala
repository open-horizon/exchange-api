package org.openhorizon.exchangeapi.table.agent.software

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}


object AgentSoftwareVersionsTQ extends TableQuery(new AgentSoftwareVersions(_)) {
  def getAgentSoftwareVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.softwareVersion)
}
