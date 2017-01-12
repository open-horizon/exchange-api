package exchangeapi

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import com.horizon.exchangeapi._

/**
 * Tests for the Version and VersionRange case classes
 */
@RunWith(classOf[JUnitRunner])
class VersionSuite extends FunSuite {
  test("Version tests") {
    assert(Version("1.2.3").isValid)
    assert(Version("infinity").isValid)
    assert(!Version("1.2.3.4").isValid)
    assert(!Version("x").isValid)
    assert(Version("1.2.3").toString === "1.2.3")
    assert(Version("1.0.0") === Version("1"))
    assert(Version("1.2.3") != Version("1.3.2"))
    assert(Version("2.2.3") > Version("1.3.2"))
    assert(!(Version("1.2.3") > Version("1.3.2")))
    assert(Version("infinity") > Version("1.3.2"))
    assert(!(Version("1.2.3") > Version("infinity")))
    assert(Version("1.2.3") >= Version("1.2.3"))
    assert(Version("1.3.3") >= Version("1.2.3"))
    assert(!(Version("1.2.2") >= Version("1.2.3")))
  }

  test("VersionRange tests") {
    assert(VersionRange("1").toString === "[1.0.0,infinity)")
    assert(!VersionRange("1,x").isValid)
    assert(VersionRange("1,infinity]").isValid)
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
    assert(Version("1.2.3") in VersionRange("1.2,1.4"))
    assert(Version("1.2.3") in VersionRange("[1.0.0,2.0.0)"))
    assert(Version("1.0.0") in VersionRange("[1.0.0,2.0.0)"))
    assert(Version("2.0.0") notIn VersionRange("[1.0.0,2.0.0)"))
  }
}