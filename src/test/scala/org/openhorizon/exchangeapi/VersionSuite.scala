package org.openhorizon.exchangeapi

import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import org.openhorizon.exchangeapi._
import org.openhorizon.exchangeapi.utility.{Version, VersionRange}

/**
 * Tests for the Version and VersionRange case classes
 */
@RunWith(classOf[JUnitRunner])
class VersionSuite extends AnyFunSuite {
  test("Version tests") {
    assert(Version("1.2.3").isValid)
    assert(Version("infinity").isValid)
    assert(Version("Infinity").isValid)
    assert(Version("INFINITY").isValid)
    assert(!Version("1.2.3.4").isValid)
    assert(!Version("x").isValid)
    assert(Version("1.2.3").toString === "1.2.3")
    assert(Version("1.0.0") === Version("1"))
    assert(Version("1.2.3") != Version("1.3.2"))
    assert(Version("2.2.3") > Version("1.3.2"))
    assert(!(Version("1.2.3") > Version("1.3.2")))
    assert(Version("infinity") > Version("1.3.2"))
    assert(!(Version("1.2.3") > Version("INFINITY")))
    assert(Version("1.2.3") >= Version("1.2.3"))
    assert(Version("1.3.3") >= Version("1.2.3"))
    assert(!(Version("1.2.2") >= Version("1.2.3")))
  }

  test("VersionRange tests") {
    assert(VersionRange("1").toString === "[1.0.0,infinity)")
    assert(!VersionRange("1,x").isValid)
    assert(VersionRange("1,infinity]").isValid)
    assert(VersionRange("1,INFINITY]").isValid)
    assert(VersionRange("1").isValid)
    assert(Version("1.2") in VersionRange("1"))
    assert(Version("1.2") notIn VersionRange(" 1, 1.1"))
    assert(Version("1.2") in VersionRange("1.2"))
    assert(Version("1.2") notIn VersionRange("(1.2"))
    assert(Version("1.2.3") in VersionRange("1.2,1.2.3]"))
    assert(Version("1.2.3") in VersionRange("1.2"))
    assert(Version("1.2.3") in VersionRange("1.2,"))
    assert(Version("1.2.3") notIn VersionRange("(1.2,1.2.3"))
    assert(Version("1.2.3") notIn VersionRange("(1.2,1.2.3)"))
    assert(Version("1.2.3") in VersionRange("1.2,infinity"))
    assert(Version("1.2.3") in VersionRange("1.2,INFINITY"))
    assert(Version("1.2.3") in VersionRange("1.2,1.4"))
    assert(Version("1.2.3") in VersionRange("[1.0.0,2.0.0)"))
    assert(Version("1.0.0") in VersionRange("[1.0.0,2.0.0)"))
    assert(Version("2.0.0") notIn VersionRange("[1.0.0,2.0.0)"))
  }
}