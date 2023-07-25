package org.openhorizon.exchangeapi.table.schema

final case class Schema(id: Int,
                        schemaVersion: Int,
                        description: String,
                        lastUpdated: String)
