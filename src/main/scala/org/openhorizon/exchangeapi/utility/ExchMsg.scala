package org.openhorizon.exchangeapi.utility

import com.osinka.i18n.{Lang, Messages}

// Returns a msg from the translated files, with the args substituted
object ExchMsg {
  def translate(key: String, args: Any*): String = {
    try {
      //todo: remove these 2 debug statements
      val exchLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
      if (exchLang.startsWith("zh") || exchLang.startsWith("pt")) println("using lang for msgs: " + exchLang)
      
      implicit val userLang: Lang = Lang(sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en")))
      if (args.nonEmpty) {
        return Messages(key, args: _*)
      }
      Messages(key)
    } catch {
      case e: Exception => s"message key '$key' not found in the messages file: ${e.getMessage}"
    }
  }
  
  def getLang: String = sys.env.getOrElse("HZN_EXCHANGE_LANG", sys.env.getOrElse("LANG", "en"))
}
