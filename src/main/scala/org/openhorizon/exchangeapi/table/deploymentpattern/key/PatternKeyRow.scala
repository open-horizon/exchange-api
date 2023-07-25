package org.openhorizon.exchangeapi.table.deploymentpattern.key

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatternKeyRow(keyId: String,
                               patternId: String,
                               key: String,
                               lastUpdated: String) {
  def toPatternKey: PatternKey = PatternKey(key, lastUpdated)

  def upsert: DBIO[_] = PatternKeysTQ.insertOrUpdate(this)
}
