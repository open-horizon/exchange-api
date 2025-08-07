package org.openhorizon.exchangeapi.table.apikey

import java.time.Instant
import java.util.UUID

final case class ApiKeyRow(createdAt: Instant,
                           createdBy: UUID,
                           description: Option[String] = None,
                           hashedKey: String,
                           id: UUID,
                           modifiedAt: Instant,
                           modifiedBy: UUID,
                           orgid: String,
                           user: UUID,
                           label: Option[String] = None)
