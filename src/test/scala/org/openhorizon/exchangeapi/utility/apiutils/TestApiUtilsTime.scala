package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.utility.{ApiTime}
import org.scalatest.funsuite.AnyFunSuite

object DateTimeConstants {
  val DateTimePattern: String = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{1,9}Z\[UTC\]"""
  val TimeLength: Int = 29
}

class TestApiUtilsTime extends AnyFunSuite {
  test("ApiTime fixFormatting no milliseconds") {
    val timeNoMilliseconds = "2019-06-17T21:24:55Z[UTC]"
    info(ApiTime.fixFormatting(timeNoMilliseconds) + " , " + "2019-06-17T21:24:55.000Z[UTC]")
    assert(ApiTime.fixFormatting(timeNoMilliseconds) == "2019-06-17T21:24:55.000Z[UTC]")
  }

  test("ApiTime fixFormatting no seconds and no milliseconds") {
    val timeNoSeconds = "2019-06-17T21:24Z[UTC]"
    info(ApiTime.fixFormatting(timeNoSeconds) + " , " + "2019-06-17T21:24:00.000Z[UTC]")
    assert(ApiTime.fixFormatting(timeNoSeconds) == "2019-06-17T21:24:00.000Z[UTC]")
  }

  test("ApiTime fixFormatting handles invalid format") {
    val invalidTime = "invalid-time-format"
    info(ApiTime.fixFormatting(invalidTime) + " , " + "Invalid format")
    assert(ApiTime.fixFormatting(invalidTime) == "Invalid format")
  }

  test("ApiTime.nowUTC is always right length") {
    info(ApiTime.nowUTC)
    assert(ApiTime.nowUTC.length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.nowUTC returns current time in valid format") {
    val currentTime = ApiTime.nowUTC
    info(currentTime)
    assert(currentTime.matches(DateTimeConstants.DateTimePattern))
  }

  test("ApiTime.thenUTC is always right time and length") {
    info(ApiTime.thenUTC(1615406509) + " , 2021-03-10T20:01:49.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406509) == "2021-03-10T20:01:49.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406509).length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.thenUTC is always right time and length test 2"){
    info(ApiTime.thenUTC(1615406355)+ " , 2021-03-10T19:59:15.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406355) == "2021-03-10T19:59:15.000Z[UTC]")
    assert(ApiTime.thenUTC(1615406355).length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.thenUTC handles future timestamps") {
    val futureTimestamp = System.currentTimeMillis() / 1000 + 10000
    val futureTime = ApiTime.thenUTC(futureTimestamp)
    info(futureTime)
    assert(futureTime.length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.thenUTC handles past timestamps") {
    val pastTimestamp = System.currentTimeMillis() / 1000 - 10000
    val pastTime = ApiTime.thenUTC(pastTimestamp)
    info(pastTime)
    assert(pastTime.length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.pastUTC is always right length") {
    info(ApiTime.pastUTC(10))
    assert(ApiTime.pastUTC(10).length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.pastUTC returns correct time") {
    val tenSecondsAgo = ApiTime.pastUTC(10)
    info(tenSecondsAgo)
    assert(tenSecondsAgo.matches(DateTimeConstants.DateTimePattern))
  }

  test("ApiTime.futureUTC is always right length") {
    info(ApiTime.futureUTC(10))
    assert(ApiTime.futureUTC(10).length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.futureUTC returns correct time") {
    val tenSecondsLater = ApiTime.futureUTC(10)
    info(tenSecondsLater)
    assert(tenSecondsLater.matches(DateTimeConstants.DateTimePattern))
  }

  test("ApiTime.beginningUTC is always correct and right length") {
    info(ApiTime.beginningUTC + " , " + "1970-01-01T00:00:00.000Z[UTC]")
    assert(ApiTime.beginningUTC == "1970-01-01T00:00:00.000Z[UTC]")
    assert(ApiTime.beginningUTC.length >= DateTimeConstants.TimeLength)
  }

  test("ApiTime.beginningUTC is formatted correctly") {
    val beginningTime = ApiTime.beginningUTC
    info(beginningTime)
    assert(beginningTime.matches(DateTimeConstants.DateTimePattern))
  }
}
