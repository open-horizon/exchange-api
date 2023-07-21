package org.openhorizon.exchangeapi.table.service

case class SearchServiceKey(architecture: String = "%",
                            domain: String,
                            organization: String,
                            session: String,
                            version: String = "%")
