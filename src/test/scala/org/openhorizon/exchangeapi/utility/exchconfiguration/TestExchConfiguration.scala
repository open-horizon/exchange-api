package org.openhorizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.utility.Configuration

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach

class TestConfiguration extends AnyFunSuite with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    Configuration.reload()
  }

  test("Should load the configuration successfully") {
      val config: Config = Configuration.getConfig
      assert(config != null)
  }

  test("Should validate necessary configuration keys") {
    val config: Config = Configuration.getConfig
    assert(config.hasPath("api"))
    assert(config.hasPath("exchange-db-connection"))
  }

  test ("Should set logback properties correctly") {
    val config: Config = Configuration.getConfig
    // Extract expected values from the config
    val logbackConfigValues = Map(
      "log.logback.appenderrefmodelhandler" -> config.getString("logback.core.model.processor.AppenderRefModelHandler"),
      "log.logback.loggermodelhandler" -> config.getString("logback.classic.model.processor.LoggerModelHandler"),
      "log.hikari.config" -> config.getString("logback.hikari.HikariConfig"),
      "log.hikari.datasource" -> config.getString("logback.hikari.HikariDataSource"),
      "log.hikari.pool" -> config.getString("logback.hikari.pool.HikariPool"),
      "log.hikari.pool.base" -> config.getString("logback.hikari.pool.PoolBase"),
      "log.caffeinecache" -> config.getString("logback.scalacache.caffeine.CaffeineCache"),
      "log.action" -> config.getString("logback.slick.basic.BasicBackend.action"),
      "log.stream" -> config.getString("logback.slick.basic.BasicBackend.stream"),
      "log.qcomp" -> config.getString("logback.slick.compiler-log"),
      "log.jdbc.driver" -> config.getString("logback.slick.jdbc.DriverDataSource"),
      "log.jdbc.bench" -> config.getString("logback.slick.jdbc.JdbcBackend.benchmark"),
    )

    logbackConfigValues.foreach { case (key, expectedValue) =>
      assert(System.getProperty(key) == expectedValue, "Expected $key to be $expectedValue, but was ${System.getProperty(key)}")
    }
  }

  test ("Allow reloading of configuration") {
      val initialConfig: Config = Configuration.getConfig

      // Simulate a reload
      Configuration.reload()
      val reloadedConfig: Config = Configuration.getConfig

      // Verify that the config remains the same (or check for specific changes)
      assert(initialConfig == reloadedConfig, "Reloaded config should be the same as initial")
  }

  test ("Should have correct ExchConfig values") {
    // Assertions based on the expected values in your config.json
    assert(Configuration.getConfig.getInt("api.limits.maxNodes") === 45000)
    assert(Configuration.getConfig.getInt("api.limits.maxAgreements") === 0)
    assert(Configuration.getConfig.getInt("api.limits.maxMessagesInMailbox") === 0)
    assert(Configuration.getConfig.getString("exchange-db-connection.dataSourceClass") === "org.postgresql.ds.PGSimpleDataSource")

    // Uncomment if you want to test these as well
    // assert(Configuration.getConfig.getInt("api.db.minPoolSize") === 1)
    // assert(Configuration.getConfig.getInt("api.db.acquireIncrement") === 1)
    // assert(Configuration.getConfig.getInt("api.db.maxPoolSize") === 50)
  }
}
