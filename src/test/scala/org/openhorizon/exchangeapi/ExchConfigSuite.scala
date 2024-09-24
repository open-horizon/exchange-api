package org.openhorizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.utility.Configuration

/**
 * Tests for the Version and VersionRange case classes
 */
@RunWith(classOf[JUnitRunner])
class ExchConfigSuite extends AnyFunSuite {
  test("ExchConfig tests") {
    Configuration.reload()
    // Note: this test needs to work with the default version of config.json that is in src/main/resources (so that 'make test' in travis works)
    assert(Configuration.getConfig.getInt("api.limits.maxNodes") === 45000)
    assert(Configuration.getConfig.getInt("api.limits.maxAgreements") === 0)
    assert(Configuration.getConfig.getInt("api.limits.maxMessagesInMailbox") === 0)
    assert(Configuration.getConfig.getString("exchange-db-connection.dataSourceClass") === "org.postgresql.ds.PGSimpleDataSource")
    // assert(Configuration.getConfig.getInt("api.db.minPoolSize") === 1)
    // assert(Configuration.getConfig.getInt("api.db.acquireIncrement") === 1)
    // assert(Configuration.getConfig.getInt("api.db.maxPoolSize") === 50)
  }
}