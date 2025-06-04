package org.openhorizon.exchangeapi.table.deploymentpattern.key

import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import slick.jdbc.PostgresProfile.api._
import slick.model.ForeignKeyAction

class PatternKeys(tag: Tag) extends Table[PatternKeyRow](tag, "patternkeys") {
  def keyId = column[String]("keyid")     // key - the key name
  def patternId = column[String]("patternid")               // additional key - the composite orgid/patternid
  def key = column[String]("key")                   // the actual key content
  def lastUpdated = column[String]("lastupdated")
  
  def * = (keyId, patternId, key, lastUpdated).<>(PatternKeyRow.tupled, PatternKeyRow.unapply)
  
  def primKey = primaryKey("pk_ptk", (keyId, patternId))
  def pattern = foreignKey("pattern_fk", patternId, PatternsTQ)(_.pattern, onUpdate=ForeignKeyAction.Cascade, onDelete=ForeignKeyAction.Cascade)
  def idx_deploy_pattern_keys_fk_patterns = index(name = "idx_deploy_pattern_keys_fk_patterns", on = patternId, unique = false)
}
