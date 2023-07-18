package org.openhorizon.exchangeapi

import org.openhorizon.exchangeapi.ApiTime.fixFormatting
import org.scalatest.funsuite.AnyFunSuite

import java.time.{ZoneId, ZonedDateTime}

class someTest extends AnyFunSuite{
  test("") {
    val a = ApiTime.nowUTCTimestamp
    
    
    info("a:                " + fixFormatting(a.toString))
    info("fixFormatting:    " + fixFormatting(a.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString))
    info("toInstant:        " + a.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString)
    info("ApiTime.thenUTC:  " + fixFormatting(ApiTime.thenUTC(a.getTime / 1000)))
    info("nowUTC:           " + ApiTime.nowUTC)
    
    info("")
    info("convert:          " + java.sql.Timestamp.from(ZonedDateTime.parse(fixFormatting(a.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString)).toInstant))
    
    val b: Option[String] = Option(fixFormatting(a.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString))
    
    val c: Option[java.sql.Timestamp] =
      b match {
      case Some("") => None
      case None => None
      case _ => Option(java.sql.Timestamp.from(ZonedDateTime.parse(b.get).toInstant))
    }
    
    info("c:                " + c)
    
    val d: Option[String] = Option("")
    val e: Option[java.sql.Timestamp] =
      d match {
      case Some("") => None
      case None => None
      case _ => Option(java.sql.Timestamp.from(ZonedDateTime.parse(d.get).toInstant))
    }
    info("e:                " + e)
    
    val f: Option[String] = None
    val g: Option[java.sql.Timestamp] =
      f match {
      case Some("") => None
      case None => None
      case _ => Option(java.sql.Timestamp.from(ZonedDateTime.parse(f.get).toInstant))
    }
    info("g:                " + g)
    
    assert(true)
  }
}
