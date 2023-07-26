package org.openhorizon.exchangeapi.utility

import scala.util.matching.Regex

object NodeAgbotTokenValidation {
  def isValid(token: String): Boolean = {
    // Check if token is valid
    // (?=.*[0-9]) digit must occur at least once
    // (?=.*[a-z]) lowercase letter must occur at least once
    // (?=.*[A-Z]) uppercase letter must occur at least once
    // .{15,} minimum 15 chars
    val exchLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
    val pwRegex: Regex = if (exchLang.contains("ja") || exchLang.contains("ko") || exchLang.contains("zh")) """^(?=.*[0-9]).{15,}$""".r else """^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).{15,}$""".r
    val valid: Boolean = token match {
      case pwRegex(_*) => true
      case _ => false
    }
    valid
  }
}
