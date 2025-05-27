package org.openhorizon.exchangeapi.table.apikey
import java.sql.Timestamp
import java.util.UUID

final case class ApiKeyRow(orgid: String,
                           id: UUID, 
                           user: UUID,
                           description: String,
                           hashedKey: String,
                           createdAt: java.sql.Timestamp,
                           createdBy: UUID,
                           modifiedAt: java.sql.Timestamp,
                           modifiedBy: UUID)
