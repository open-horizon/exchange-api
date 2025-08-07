package org.openhorizon.exchangeapi.utility
import java.time.format.DateTimeParseException

import java.time.{Instant, ZoneId, ZonedDateTime}

/** Convenience methods for setting and comparing lastUpdated and lastHeartbeat attributes */
object ApiTime {
  /** Returns now in UTC string format */
  def nowUTC: String = nowUTCTimestamp.toString
  
  /** Returns now in UTC in java.sql.Timestamp type */
  def nowUTCTimestamp: Instant = Instant.now()
  
  /** Validates time format */
  def isValidTimeFormat(time: String): Boolean = {
    try {
      ZonedDateTime.parse(time)
      true
    } catch {
      case _: DateTimeParseException => false
      case _: Exception => false
    }
  }
}
