package org.openhorizon.exchangeapi.table.node

/** 1 service in the search criteria */
final case class RegServiceSearch(url: String,
                                  properties: List[Prop])
  extends RegServiceTrait
