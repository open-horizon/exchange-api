package org.openhorizon.exchangeapi
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.utility.{Version, VersionRange}

/**
 * Tests for the Version Range
 */
@RunWith(classOf[JUnitRunner])
class TestVersionRange extends AnyFunSuite with Matchers {
  test("VersionRange tests") {
    // Basic string representation tests
    assert(VersionRange("1").toString === "[1.0.0,infinity)")
    assert(VersionRange("1,infinity]").toString === "[1.0.0,infinity]")
    assert(VersionRange("1.2,2").toString === "[1.2.0,2.0.0)")

    // Validity tests
    assert(!VersionRange("1,x").isValid)        // Invalid due to non-numeric
    assert(VersionRange("1,infinity]").isValid) // Valid range with infinity
    assert(VersionRange("1,INFINITY]").isValid) // Case insensitivity
    assert(VersionRange("1").isValid)           // Single version as valid range
    assert(VersionRange("1.0.0").isValid)       // Valid single version
    assert(VersionRange("1.2,2.0.0").isValid)   // Valid range

    // Inclusion tests
    assert(Version("1.2") in VersionRange("1"))                  // Included in range starting with 1
    assert(Version("1.2") notIn VersionRange("1, 1.1"))          // Not included in this range
    assert(Version("1.2") in VersionRange("1.2"))                // Exact match
    assert(Version("1.2") notIn VersionRange("(1.2"))            // Not included due to exclusive start
    assert(Version("1.2.3") in VersionRange("1.2,1.2.3]"))       // Included in inclusive end range
    assert(Version("1.2.3") in VersionRange("1.2"))              // Included in single version range
    assert(Version("1.2.3") in VersionRange("1.2,"))             // Open-ended range
    assert(Version("1.2.3") notIn VersionRange("(1.2,1.2.3"))    // Exclusive lower bound
    assert(Version("1.2.3") notIn VersionRange("(1.2,1.2.3)"))   // Exclusive bounds
    assert(Version("1.2.3") in VersionRange("1.2,infinity"))     // Open-ended to infinity
    assert(Version("1.2.3") in VersionRange("1.2,INFINITY"))     // Case insensitivity for infinity
    assert(Version("1.2.3") in VersionRange("1.2,1.4"))          // Included in this range
    assert(Version("1.2.3") in VersionRange("[1.0.0,2.0.0)"))    // Within valid range
    assert(Version("1.0.0") in VersionRange("[1.0.0,2.0.0)"))    // Exact match
    assert(Version("2.0.0") notIn VersionRange("[1.0.0,2.0.0)")) // Outside range
  }

  test("Additional VersionRange edge cases") {
    // Test for overlapping ranges
    assert(Version("1.5") in VersionRange("1.0,1.6"))      // In overlapping range
    assert(Version("1.0") in VersionRange("[1.0,1.5)"))    // Lower bound inclusive
    assert(Version("1.5") in VersionRange("(1.0,2.0)"))    // Upper bound exclusive
    assert(Version("1.0") notIn VersionRange("(1.0,1.5)")) // Exclusive lower bound

    // Edge case with negative version numbers
    assert(!VersionRange("-1.0,0").isValid) // Invalid range with negative version
  }

  test("Invalid VersionRange formats") {
    assert(!VersionRange("").isValid)         // Empty string
    assert(!VersionRange(",2").isValid)       // Invalid start
    assert(!VersionRange("1,2,3").isValid)    // Too many elements
    assert(!VersionRange("1.0,)2.0").isValid) // Unmatched parentheses and invalid character
  }
}
