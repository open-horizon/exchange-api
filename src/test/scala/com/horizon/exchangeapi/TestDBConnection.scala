package com.horizon.exchangeapi

import com.horizon.exchangeapi.ExchangeApiApp.logger
import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.util.AsyncExecutor
import slick.jdbc.PostgresProfile.api.Database

class TestDBConnection {
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.getHostAndPort match {
    case (h, pe, pu) =>
      ExchangeApi.serviceHost = h
      ExchangeApi.servicePortEncrypted = pe
      ExchangeApi.servicePortUnencrypted = pu
  }
  
  val maxPoolSizeConfig: Int = ExchConfig.getInt("api.db.maxPoolSize")
  
  var cpds: ComboPooledDataSource = new ComboPooledDataSource()
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setIdleConnectionTestPeriod(ExchConfig.getInt("api.db.idleConnectionTestPeriod"))
  cpds.setInitialPoolSize(ExchConfig.getInt("api.db.initialPoolSize"))
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setMaxConnectionAge(ExchConfig.getInt("api.db.maxConnectionAge"))
  cpds.setMaxIdleTimeExcessConnections(ExchConfig.getInt("api.db.maxIdleTimeExcessConnections"))
  cpds.setMaxPoolSize(maxPoolSizeConfig)
  cpds.setMaxStatementsPerConnection(ExchConfig.getInt("api.db.maxStatementsPerConnection"))
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setNumHelperThreads(ExchConfig.getInt("api.db.numHelperThreads"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  cpds.setTestConnectionOnCheckin(ExchConfig.getBoolean("api.db.testConnectionOnCheckin"))
  cpds.setUser(ExchConfig.getString("api.db.user"))
  
  // maxConnections, maxThreads, and minThreads should all be the same size.
  val db: Database =
    if (cpds != null) {
      Database.forDataSource(ds = cpds,
                             executor = AsyncExecutor(name = "ExchangeExecutor",
                                                      maxConnections = maxPoolSizeConfig,
                                                      maxThreads = maxPoolSizeConfig,
                                                      minThreads = maxPoolSizeConfig,
                                                      queueSize = ExchConfig.getInt("api.db.queueSize")),
                             maxConnections = Option(maxPoolSizeConfig))
    }
    else
      null
  
  def getDb: Database = db
}
