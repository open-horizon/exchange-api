package org.openhorizon.exchangeapi.route.deploymentpattern

import org.openhorizon.exchangeapi.table.deploymentpattern.key.{PatternKey, PatternKeyRow}
import org.openhorizon.exchangeapi.utility.ApiTime

final case class PutPatternKeyRequest(key: String) {
  require(key!=null)
  def toPatternKey: PatternKey = PatternKey(key, ApiTime.nowUTC)
  def toPatternKeyRow(patternId: String, keyId: String): PatternKeyRow = PatternKeyRow(keyId, patternId, key, ApiTime.nowUTC)
  def getAnyProblem: Option[String] = None
}
