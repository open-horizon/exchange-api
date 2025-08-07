package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.utility.{ApiTime}
import org.scalatest.funsuite.AnyFunSuite

object DateTimeConstants {
  val DateTimePattern: String = """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{1,9}Z\[UTC\]"""
  val TimeLength: Int = 29
}

class TestApiUtilsTime extends AnyFunSuite {
  test("ApiTime.nowUTC is always right length") {
    info(ApiTime.nowUTC)
    assert(ApiTime.nowUTC.length >= DateTimeConstants.TimeLength)
  }
}
