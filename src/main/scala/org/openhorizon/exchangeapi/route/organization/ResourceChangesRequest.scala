package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}

import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

final case class ResourceChangesRequest(changeId: Long,
                                        lastUpdated: Option[String],
                                        maxRecords: Int,
                                        orgList: Option[List[String]]) {
  def getAnyProblem: Option[String] = {
    try {
      ZonedDateTime.parse(lastUpdated.getOrElse(ApiTime.beginningUTC)) //if lastUpdated is provided, make sure we can parse it
      None //if the parse was successful, no problem
    }
    catch {
      case _: DateTimeParseException => Some.apply(ExchMsg.translate("error.parsing.timestamp", lastUpdated.get))
    }
  }
}
