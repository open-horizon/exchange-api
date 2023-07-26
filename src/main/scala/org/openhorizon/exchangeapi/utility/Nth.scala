package org.openhorizon.exchangeapi.utility

/** Depending on the given int, returns 1st, 2nd, 3rd, 4th, ... */
final case class Nth(n: Int) {
  override def toString: String = {
    n match {
      case 1 => s"${n}st"
      case 2 => s"${n}nd"
      case 3 => s"${n}rd"
      case _ => s"${n}th"
    }
  }
}
