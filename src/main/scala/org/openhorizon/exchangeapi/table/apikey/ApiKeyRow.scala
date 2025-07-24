package org.openhorizon.exchangeapi.table.apikey
import java.sql.Timestamp
import java.util.UUID

final case class ApiKeyRow(createdAt: java.sql.Timestamp,
                           createdBy: UUID,
                           description: Option[String] = None,
                           hashedKey: String,
                           id: UUID,
                           modifiedAt: java.sql.Timestamp,
                           modifiedBy: UUID,
                           orgid: String,
                           user: UUID,
                           label: Option[String] = None)
