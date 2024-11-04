package org.openhorizon.exchangeapi.utility
import java.time.format.DateTimeParseException

import java.time.{Instant, ZoneId, ZonedDateTime}

/** Convenience methods for setting and comparing lastUpdated and lastHeartbeat attributes */
object ApiTime {
  /** Returns now in UTC string format */
  def nowUTC: String = fixFormatting(ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toString)
  
  /** Returns now in UTC in java.sql.Timestamp type */
  def nowUTCTimestamp: java.sql.Timestamp = java.sql.Timestamp.from(ZonedDateTime.now.withZoneSameInstant(ZoneId.of("UTC")).toInstant)
  
  /** Return UTC format of the time specified in seconds */
  def thenUTC(seconds: Long): String = fixFormatting(ZonedDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.of("UTC")).toString)
  
  /** Return UTC format of the time n seconds ago */
  def pastUTC(secondsAgo: Int): String = fixFormatting(ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toString)
  
  /** Return UTC format of the time n seconds ago in java.sql.Timestamp type */
  def pastUTCTimestamp(secondsAgo: Int): java.sql.Timestamp = java.sql.Timestamp.from(ZonedDateTime.now.minusSeconds(secondsAgo).withZoneSameInstant(ZoneId.of("UTC")).toInstant)
  
  /** Return UTC format of the time n seconds from now */
  def futureUTC(secondsFromNow: Int): String = fixFormatting(ZonedDateTime.now.plusSeconds(secondsFromNow).withZoneSameInstant(ZoneId.of("UTC")).toString)
  
  /** Return UTC format of unix begin time */
  def beginningUTC: String = fixFormatting(ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC")).toString)
  
  /** Returns now in epoch seconds */
  def nowSeconds: Long = System.currentTimeMillis / 1000 // seconds since 1/1/1970
  
  /** Returns now as a java.sql.Timestamp */
  def nowTimestamp = new java.sql.Timestamp(System.currentTimeMillis())
  
  /** Determines if the given UTC time is more than daysStale old */
  def isDaysStale(UTC: String, daysStale: Int): Boolean = {
    if (daysStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val secondsInDay = 86400
    val thenTime: Long = ZonedDateTime.parse(UTC).toEpochSecond
    (nowSeconds - thenTime >= daysStale * secondsInDay)
  }
  
  /** Determines if the given UTC time is more than secondsStale old */
  def isSecondsStale(UTC: String, secondsStale: Int): Boolean = {
    if (secondsStale <= 0) return false // they did not specify what was too stale
    if (UTC == "") return true // assume an empty UTC is the beginning of time
    val thenTime: Long = ZonedDateTime.parse(UTC).toEpochSecond
    (nowSeconds - thenTime >= secondsStale)
  }
  
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
  
  def fixFormatting(time: String): String = {
    if (time.isEmpty || !isValidTimeFormat(time)) {
      return "Invalid format"
    }
    val timeLength: Int = time.length
    /*
     This implementation uses length of the string instead of a regex to make it as fast as possible
     The problem that was happening is described here: https://bugs.openjdk.java.net/browse/JDK-8193307
     Essentially the returned string would truncate milliseconds or seconds and milliseconds if those values happened to be 0
     So we would be getting:
     uuuu-MM-dd'T'HH:mm (Ex: "2020-02-05T20:28Z[UTC]")
     uuuu-MM-dd'T'HH:mm:ss (Ex: "2020-02-05T20:28:14Z[UTC]")
     Instead of what we want : uuuu-MM-dd'T'HH:mm:ss.SSS  (Ex: "2020-02-05T20:28:14.469Z[UTC]")
     This implementation serves to ensure we always get time in the format we expect
     This is explained in the docs here: https://docs.oracle.com/javase/9/docs/api/java/time/LocalDateTime.html#toString--
     length when time is fully filled out is 29
     length when time has no milliseconds 25
     length when time has no seconds and no milliseconds is 22
     */
    if (timeLength >= 29) { // if its the correct length just return it
      time
    } else if (timeLength == 25) { // need to add milliseconds on
      time.substring(0, 19) + ".000Z[UTC]"
    } else if (timeLength == 22) { // need to add seconds and milliseconds on
      time.substring(0, 16) + ":00.000Z[UTC]"
    } else time // On the off chance its some weird length
  }
}
