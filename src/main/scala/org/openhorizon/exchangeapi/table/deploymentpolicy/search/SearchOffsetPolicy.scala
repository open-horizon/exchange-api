package org.openhorizon.exchangeapi.table.deploymentpolicy.search

import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class SearchOffsetPolicy(tag: Tag) extends Table[SearchOffsetPolicyAttributes](tag, "search_offset_policy") {
  def agbot = column[String]("agbot")
  def offset = column[Option[String]]("offset", O.Default(None))
  def policy = column[String]("policy")
  def session = column[Option[String]]("session", O.Default(None))
  
  def pkSearchOffsetsPolicy = primaryKey("pk_searchoffsetpolicy", (agbot, policy))
  def fkAgbot = foreignKey("fk_agbot", agbot, AgbotsTQ)(_.id, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def fkPolicy = foreignKey("fk_policy", policy, BusinessPoliciesTQ)(_.businessPolicy, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  
  def * = (agbot, offset, policy, session).mapTo[SearchOffsetPolicyAttributes]
  def idx_search_offset_pol_fk_deploy_pols = index(name = "idx_search_offset_pol_fk_deploy_pols", on = policy, unique = false)
}
