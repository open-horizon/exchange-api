package org.openhorizon.exchangeapi.auth

// Enum for type of id in CacheId class
object CacheIdType extends Enumeration {
  type CacheIdType = Value
  val User: Value = Value("User")
  val Node: Value = Value("Node")
  val Agbot: Value = Value("Agbot")
  val None: Value = Value("None")
}
