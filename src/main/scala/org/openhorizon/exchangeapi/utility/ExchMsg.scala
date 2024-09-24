package org.openhorizon.exchangeapi.utility

import com.osinka.i18n.{Lang, Messages}

// Returns a msg from the translated files, with the args substituted
object ExchMsg {
  def translate(key: String, args: Any*): String = {
    try {
      //todo: remove these 2 debug statements
      val exchLang: String = getLang
      if (exchLang.startsWith("zh") || exchLang.startsWith("pt")) println("using lang for msgs: " + exchLang)
      
      implicit val userLang: Lang = Lang(exchLang)
      if (args.nonEmpty) {
        return Messages(key, args: _*)
      }
      Messages(key)
    } catch {
      case e: Exception => s"message key '$key' not found in the messages file: ${e.getMessage}"
    }
  }
  
  def getLang: String =
    try
      Configuration.getConfig.getString("api.language")
    catch {
      case _: Exception => "en"
    }
}
