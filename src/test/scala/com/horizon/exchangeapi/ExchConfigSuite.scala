package com.horizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import com.horizon.exchangeapi._

/**
 * Tests for the Version and VersionRange case classes
 */
@RunWith(classOf[JUnitRunner])
class ExchConfigSuite extends AnyFunSuite {
  test("ExchConfig tests") {
    ExchConfig.load()
    // Note: this test needs to work with the default version of config.json that is in src/main/resources (so that 'make test' in travis works)
    assert(ExchConfig.getInt("api.limits.maxNodes") === 10000)
    assert(ExchConfig.getInt("api.limits.maxAgreements") === 0)
    assert(ExchConfig.getInt("api.limits.maxMessagesInMailbox") === 10000)
    assert(ExchConfig.getString("api.db.driverClass") === "org.postgresql.Driver")
    assert(ExchConfig.getInt("api.db.minPoolSize") === 1)
    assert(ExchConfig.getInt("api.db.acquireIncrement") === 1)
    assert(ExchConfig.getInt("api.db.maxPoolSize") === 50)
  }
}