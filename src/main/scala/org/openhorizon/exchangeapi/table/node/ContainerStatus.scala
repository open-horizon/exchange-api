package org.openhorizon.exchangeapi.table.node

final case class ContainerStatus(name: String,
                                 image: String,
                                 created: Int,
                                 state: String)
