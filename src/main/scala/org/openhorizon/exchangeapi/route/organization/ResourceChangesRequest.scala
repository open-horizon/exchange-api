package org.openhorizon.exchangeapi.route.organization

import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}

import java.time.{Instant, ZonedDateTime}
import java.time.format.DateTimeParseException

final case class ResourceChangesRequest(changeId: Long,
                                        lastUpdated: Option[String],
                                        maxRecords: Int,
                                        orgList: Option[List[String]]) {
  def getAnyProblem: Option[String] = {
    try {
      if (lastUpdated.isDefined)
        ZonedDateTime.parse(lastUpdated.get) //if lastUpdated is provided, make sure we can parse it
      
      None
    }
    catch {
      case _: DateTimeParseException => Some.apply(ExchMsg.translate("error.parsing.timestamp", lastUpdated.get))
    }
  }
}
