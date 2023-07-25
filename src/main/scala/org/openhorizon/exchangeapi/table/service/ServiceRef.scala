package org.openhorizon.exchangeapi.table.service

final case class ServiceRef(url: String,
                            org: String,
                            version: Option[String],
                            versionRange: Option[String],
                            arch: String)
