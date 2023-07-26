package org.openhorizon.exchangeapi.table.agent.certificate

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}


object AgentCertificateVersionsTQ extends TableQuery(new AgentCertificateVersions(_)) {
  def getAgentCertificateVersions(organization: String): Query[Rep[String], String, Seq] = this.filter(_.organization === organization).map(_.certificateVersion)
}
