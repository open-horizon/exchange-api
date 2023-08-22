package org.openhorizon.exchangeapi.utility

object LogLevel {
  val OFF = "OFF"
  val ERROR = "ERROR"
  val WARN = "WARN"
  val INFO = "INFO"
  val DEBUG = "DEBUG"
  val validLevels: Set[String] = Set(OFF, ERROR, WARN, INFO, DEBUG)
}
