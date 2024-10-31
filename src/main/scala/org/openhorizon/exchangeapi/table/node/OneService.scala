package org.openhorizon.exchangeapi.table.node

import io.swagger.v3.oas.annotations.media.Schema

final case class OneService(agreementId: String,
                            serviceUrl: String,
                            orgid: String,
                            version: String,
                            arch: String,
                            containerStatus: List[ContainerStatus],
                            @Schema(implementation = classOf[Object]) operatorStatus: Option[Any],
                            configState: Option[String])
