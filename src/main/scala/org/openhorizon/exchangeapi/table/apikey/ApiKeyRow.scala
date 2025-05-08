package org.openhorizon.exchangeapi.table.apikey
import java.sql.Timestamp
final case class ApiKeyRow(orgid: String,
                           id: String, // UUID
                           username: String,
                           description: String,
                           hashedKey: String,
                           createdAt: String,
                           createdBy: String,
                           modifiedAt: String,
                           modifiedBy: String)
