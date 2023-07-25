package org.openhorizon.exchangeapi.table.deploymentpolicy.search

import slick.dbio.{Effect, NoStream}
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Rep, TableQuery}
import slick.sql.FixedSqlAction

object SearchOffsetPolicyTQ  extends TableQuery(new SearchOffsetPolicy(_)){
  def dropAllOffsets(): FixedSqlAction[Int, NoStream, Effect.Write] =
    this.delete
  
  def getOffsetSession(agbot: String, policy: String): Query[(Rep[Option[String]], Rep[Option[String]]), (Option[String], Option[String]), Seq] =
    this
      .filter(_.agbot === agbot)
      .filter(_.policy === policy)
      .map(offset => (offset.offset, offset.session))
      
  def setOffsetSession(agbot: String, offset: Option[String], policy: String, session: Option[String]): FixedSqlAction[Int, NoStream, Effect.Write] =
    this.insertOrUpdate(SearchOffsetPolicyAttributes(agbot, offset, policy, session))
}
