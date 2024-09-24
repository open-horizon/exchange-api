package org.openhorizon.exchangeapi.utility

import slick.jdbc.PostgresProfile.api._

case object DatabaseConnection {
  private val database: Database = Database.forConfig("exchange-db-connection", Configuration.getConfig)
  
  def getDatabase: Database = database
}
