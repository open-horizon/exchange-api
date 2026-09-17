package org.openhorizon.exchangeapi.table.deploymentpattern.key

import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import slick.lifted.TableQuery

object PatternKeysTQ extends TableQuery(new PatternKeys(_)) {
  def getKeys(patternId: String): Query[PatternKeys, PatternKeyRow, Seq] = this.filter(_.patternId === patternId)
  def getKey(patternId: String, keyId: String): Query[PatternKeys, PatternKeyRow, Seq] = this.filter(r => {r.patternId === patternId && r.keyId === keyId} )
}
