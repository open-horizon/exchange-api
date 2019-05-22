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
    ExchConfig.load()
    assert(ExchConfig.getInt("api.limits.maxNodes") === 10000)
    //assert(ExchConfig.getInt("api.limits.maxAgbots") === 1000)  // <- i usually locally test this as 0
    assert(ExchConfig.getInt("api.limits.maxAgreements") === 0)
    assert(ExchConfig.getInt("api.limits.maxMessagesInMailbox") === 10000)

    assert(ExchConfig.getString("api.db.driverClass") === "org.postgresql.Driver")
    assert(ExchConfig.getString("api.db.jdbcUrl").startsWith("jdbc:"))
//    assert(ExchConfig.getString("api.db.user") === "admin" || ExchConfig.getString("api.db.user") === "bp" || ExchConfig.getString("api.db.user") === "sadiyahfaruk")     // the local test db name is my user
    assert(ExchConfig.getInt("api.db.minPoolSize") === 1)
    assert(ExchConfig.getInt("api.db.acquireIncrement") === 1)
    assert(ExchConfig.getInt("api.db.maxPoolSize") === 50)
  }
}