package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.horizon.exchangeapi._

/**
 * Tests for the Version and VersionRange case classes
 */
@RunWith(classOf[JUnitRunner])
class ExchConfigSuite extends FunSuite {
  test("ExchConfig tests") {
    ExchConfig.load
    assert(ExchConfig.getInt("api.limits.maxNodes") === 1000)
    assert(ExchConfig.getInt("api.limits.maxAgbots") === 1000)
    assert(ExchConfig.getInt("api.limits.maxAgreements") === 1000)
    assert(ExchConfig.getInt("api.limits.maxMessagesInMailbox") === 500)

    assert(ExchConfig.getString("api.db.driverClass") === "org.postgresql.Driver")
    assert(ExchConfig.getString("api.db.jdbcUrl").startsWith("jdbc:"))
    assert(ExchConfig.getString("api.db.user") === "admin" || ExchConfig.getString("api.db.user") === "bp")     // the local test db name is my user
    assert(ExchConfig.getInt("api.db.minPoolSize") === 1)
    assert(ExchConfig.getInt("api.db.acquireIncrement") === 1)
    assert(ExchConfig.getInt("api.db.maxPoolSize") === 50)
  }
}