//package org.openhorizon.exchangeapi.route.version

package org.openhorizon.exchangeapi
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.utility.{Version, VersionRange}

/**
 * Tests for the Version
 */
@RunWith(classOf[JUnitRunner])
class TestVersion extends AnyFunSuite with Matchers {
  test("Version validity tests") {
    // Valid versions
    assert(Version("1.2.3").isValid)
    assert(Version("1.0.0").isValid)
    assert(Version("0.0.0").isValid)
    assert(Version("infinity").isValid)
    assert(Version("Infinity").isValid)
    assert(Version("INFINITY").isValid)

    // Invalid versions
    assert(!Version("1.2.3.4").isValid) // Too many segments
    assert(!Version("x").isValid)       // Non-numeric
    assert(!Version("").isValid)        // Empty string
    assert(!Version("1.2.a").isValid)   // Invalid character
    assert(!Version("1..2").isValid)    // Double dot
    assert(!Version("1.2..3").isValid)  // Double dot in the middle
    assert(!Version("1.2.-3").isValid)  // Negative number
    assert(!Version("-1.2.3").isValid)  // Negative number at start
    assert(!Version("1.2.3-").isValid)  // Hyphen at the end
  }

  test("Version string representation") {
    assert(Version("1.2.3").toString === "1.2.3")
    assert(Version("infinity").toString === "infinity")
    assert(Version("0.0.0").toString === "0.0.0")
  }

  test("Version equality tests") {
    assert(Version("1.0.0") === Version("1"))
    assert(Version("1.2.3") === Version("1.2.3"))
    assert(Version("1.2.3") != Version("1.3.2"))
    assert(Version("0.0.0") === Version("0.0.0"))
  }

  test("Version comparison tests") {
    assert(Version("2.2.3") > Version("1.3.2"))
    assert(Version("1.2.3") > Version("1.2.2"))
    assert(Version("1.2.3") >= Version("1.2.3"))
    assert(Version("1.3.3") >= Version("1.2.3"))
    assert(!(Version("1.2.2") >= Version("1.2.3")))
  
    assert(Version("infinity") > Version("1.3.2"))
    assert(!(Version("1.2.3") > Version("INFINITY")))
  
    // Testing with leading zeros
    assert(Version("1.2.3") === Version("01.2.3"))
    assert(Version("1.2.3") > Version("1.2.02"))
    assert(Version("1.2.3") >= Version("1.02.3"))
  }

  test("Edge cases and performance tests") {
    // Check upper limits
    assert(Version("999999999.999999999.999999999").isValid)

    // Check behavior with invalid but interesting formats
    assert(Version("1.0.0").isValid)
    assert(Version("1.0").isValid)
  }
}