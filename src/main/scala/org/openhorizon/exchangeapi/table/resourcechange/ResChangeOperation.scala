package org.openhorizon.exchangeapi.table.resourcechange

object ResChangeOperation extends Enumeration {
  type ResChangeOperation = Value
  val CREATED: ResChangeOperation.Value = Value("created")
  val CREATEDMODIFIED: ResChangeOperation.Value = Value("created/modified")
  val MODIFIED: ResChangeOperation.Value = Value("modified")
  val DELETED: ResChangeOperation.Value = Value("deleted")
}
