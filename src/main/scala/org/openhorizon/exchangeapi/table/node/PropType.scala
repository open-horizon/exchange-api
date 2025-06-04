package org.openhorizon.exchangeapi.table.node

// I do not think we can use actual enums in classes that get mapped to json and swagger
object PropType {
  val STRING = "string"
  val LIST = "list"
  val VERSION = "version"
  val BOOLEAN = "boolean"
  val INT = "int"
  val WILDCARD = "wildcard"       // means 1 side does not care what value the other side has
  val all = Set(STRING, LIST, VERSION, BOOLEAN, INT, WILDCARD)
  
  def contains(s: String): Boolean = all.contains(s)
}
