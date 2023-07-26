package org.openhorizon.exchangeapi.utility

import scala.util.matching.Regex

/** Parse a version string like 1.2.3 into its parts and define >(), in(), etc. */
final case class Version(version: String) {
  val R3: Regex = """(\d+)\.(\d+)\.(\d+)""".r
  val R2: Regex = """(\d+)\.(\d+)""".r
  val R1: Regex = """(\d+)""".r
  val (major, minor, mod, isInfinity) = version.trim().toLowerCase match {
    case "infinity" => (0, 0, 0, true)
    case R3(maj, min, mo) => (maj.toInt, min.toInt, mo.toInt, false)
    case R2(maj, min) => (maj.toInt, min.toInt, 0, false)
    case R1(maj) => (maj.toInt, 0, 0, false)
    case _ => (-1, -1, -1, false)
  }
  
  def isValid: Boolean = {
    isInfinity || (major != -1 && minor != -1 && mod != -1)
  }
  
  // the == operator calls equals()
  override def equals(that: Any): Boolean = that match {
    case that: Version => if (!isValid || !that.isValid) false else if (that.isInfinity && isInfinity) true else if (that.isInfinity || isInfinity) false else that.major == major && that.minor == minor && that.mod == mod
    case _ => false
  }
  
  def >(that: Version): Boolean = {
    if (this.isInfinity && !that.isInfinity) true else if (that.isInfinity) false else if (this.major > that.major) true else if (that.major > this.major) false else if (this.minor > that.minor) true else if (that.minor > this.minor) false else if (this.mod > that.mod) true else false
  }
  
  def >=(that: Version): Boolean = (this > that || this == that)
  
  def in(range: VersionRange): Boolean = if (!isValid || !range.isValid) false else range includes this
  
  def notIn(range: VersionRange): Boolean = if (!isValid || !range.isValid) true else !(range includes this)
  
  override def toString: String = {
    if (isInfinity) "infinity" else "" + major + "." + minor + "." + mod
  }
}
