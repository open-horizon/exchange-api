package org.openhorizon.exchangeapi.table.node

final case class OneService(agreementId: String,
                            serviceUrl: String,
                            orgid: String,
                            version: String,
                            arch: String,
                            containerStatus: List[ContainerStatus],
                            operatorStatus: Option[Any],
                            configState: Option[String])
