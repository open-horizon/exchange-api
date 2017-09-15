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
    
    assert(ExchConfig.getString("api.specRef.prefix") === "https://bluehorizon.network/documentation/")
    assert(ExchConfig.getString("api.specRef.suffix") === "-node-api")
    
    assert(ExchConfig.getString("api.objStoreTmpls.prefix") === "https://tor01.objectstorage.softlayer.net/v1/AUTH_bd05f276-e42f-4fa1-b7b3-780e8544769f")
    assert(ExchConfig.getString("api.objStoreTmpls.microDir") === "microservice-templates")
    assert(ExchConfig.getString("api.objStoreTmpls.workloadDir") === "workload-templates")
    assert(ExchConfig.getString("api.objStoreTmpls.suffix") === ".json")
    
    assert(ExchConfig.getString("api.db.driverClass") === "org.postgresql.Driver")
    assert(ExchConfig.getString("api.db.jdbcUrl").startsWith("jdbc:"))
    assert(ExchConfig.getString("api.db.user") === "admin" || ExchConfig.getString("api.db.user") === "bp")     // the local test db name is my user
    assert(ExchConfig.getInt("api.db.minPoolSize") === 1)
    assert(ExchConfig.getInt("api.db.acquireIncrement") === 1)
    assert(ExchConfig.getInt("api.db.maxPoolSize") === 50)
  }
}