package org.openhorizon.exchangeapi.table.node

/** Contains the object representations of the DB tables related to nodes. */
final case class RegService(url: String,
                            numAgreements: Int,
                            configState: Option[String],
                            policy: String,
                            properties: List[Prop],
                            version: Option[String])
  extends RegServiceTrait
