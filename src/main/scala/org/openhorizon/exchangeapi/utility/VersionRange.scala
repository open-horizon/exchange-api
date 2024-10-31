package org.openhorizon.exchangeapi.utility

import scala.util.matching.Regex

/** Parse an osgi version range string and define includes() to test if a Version is in a VersionRange */
final case class VersionRange(range: String) {
  /* The typical format of a range is like [1.2.3,4.5.6), where
  The 1st version is the lower bound (floor), if not specified 0.0.0 is the default
  The 2nd version is the upper bound (ceiling), if not specified infinity is the default
  [ or ] means inclusive on that side of the range
  ( or ) means *not* inclusive of the limit on that side of the range
  The default for the left side is [, the default for the right side is )
  For more detail, see section 3.2.6 of the OSGi Core Specification: https://www.osgi.org/developer/downloads/
  */
  // split the lower and upper bounds
  val (firstPart, secondPart) = range.trim().toLowerCase.split("""\s*,\s*""") match {
    case Array(s) => (s, "infinity")
    case Array(s1, s2) => (s1, s2)
    case _ => ("x", "x")
  }
  // split the leading [ or ( from the version number
  val R1: Regex = """([\[(]?)(\d.*)""".r
  val (floorInclusive, floor) = firstPart match {
    case "" => (true, Version("0.0.0"))
    case R1(i, f) => ((i != "("), Version(f))
    case _ => (false, Version("x")) // Version("x") is just an invalid version object
  }
  // separate the version number from the trailing ] or )
  val R2: Regex = """(.*\d)([\])]?)""".r
  val R3: Regex = """(infinity)([\])]?)""".r
  val (ceiling, ceilingInclusive) = secondPart match { // case "" => (Version("infinity"), false)
    case R2(c, i) => (Version(c), (i == "]"))
    case R3(c, i) => (Version(c), (i == "]"))
    case _ => (Version("x"), true)
  }
  
  def isValid: Boolean = {
    if (firstPart.trim.isEmpty || secondPart.trim.isEmpty || secondPart.trim.isEmpty) {
      return false
    }
    floor.isValid && ceiling.isValid
  }
  
  def includes(version: Version): Boolean = {
    if (floorInclusive) {
      if (floor > version) return false
    } else {
      if (floor >= version) return false
    }
    if (ceilingInclusive) {
      if (version > ceiling) return false
    } else {
      if (version >= ceiling) return false
    }
    true
  }
  
  // If this range is a single version (e.g. [1.2.3,1.2.3] ) return that version, otherwise None
  def singleVersion: Option[Version] = {
    if (floor == ceiling) Option(floor) else None
  }
  
  override def toString: String = {
    (if (floorInclusive) "[" else "(") + floor + "," + ceiling + (if (ceilingInclusive) "]" else ")")
  }
}
