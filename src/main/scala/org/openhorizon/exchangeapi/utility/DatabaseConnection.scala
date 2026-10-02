package org.openhorizon.exchangeapi.utility

import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._

case object DatabaseConnection {
  private val database: Database = Database.forConfig("exchange-db-connection", Configuration.getConfig)
  
  def getDatabase: Database = database
}
