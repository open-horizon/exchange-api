package org.openhorizon.exchangeapi.table.node

/** The valid operators for properties */
object Op {
  val EQUAL = "="
  val GTEQUAL = ">="
  val LTEQUAL = "<="
  val IN = "in"
  val all = Set(EQUAL, GTEQUAL, LTEQUAL, IN )
  def contains(s: String): Boolean = all.contains(s)
}
