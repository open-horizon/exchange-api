package org.openhorizon.exchangeapi.route.service

object SharableVals extends Enumeration {
  type SharableVals = Value
  val EXCLUSIVE: Value = Value("exclusive")
  val SINGLE: Value = Value("single")    // this is being replaced by singleton
  val SINGLETON: Value = Value("singleton")
  val MULTIPLE: Value = Value("multiple")
}
