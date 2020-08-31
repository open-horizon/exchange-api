package com.horizon.exchangeapi

import com.mchange.v2.c3p0.ComboPooledDataSource
import slick.util.AsyncExecutor
import slick.jdbc.PostgresProfile.api.Database

class TestDBConnection {
  ExchConfig.load() // get config file, normally in /etc/horizon/exchange/config.json
  ExchConfig.getHostAndPort match {
    case (h, p) => ExchangeApi.serviceHost = h;
      ExchangeApi.servicePort = p
  }
  
  private var cpds: ComboPooledDataSource = new ComboPooledDataSource
  cpds.setDriverClass(ExchConfig.getString("api.db.driverClass")) //loads the jdbc driver
  cpds.setJdbcUrl(ExchConfig.getString("api.db.jdbcUrl"))
  cpds.setUser(ExchConfig.getString("api.db.user"))
  cpds.setPassword(ExchConfig.getString("api.db.password"))
  // the settings below are optional -- c3p0 can work with defaults
  cpds.setMinPoolSize(ExchConfig.getInt("api.db.minPoolSize"))
  cpds.setAcquireIncrement(ExchConfig.getInt("api.db.acquireIncrement"))
  cpds.setMaxPoolSize(ExchConfig.getInt("api.db.maxPoolSize"))
  
  private val maxConns: Int = ExchConfig.getInt("api.db.maxPoolSize")
  
  private val db: Database =
    if (cpds != null) {
      Database.forDataSource(cpds,
        Some(maxConns),
        AsyncExecutor("ExchangeExecutor", maxConns, maxConns, 1000, maxConns))
    }
    else
      null
  
  def getDb: Database = db
}
