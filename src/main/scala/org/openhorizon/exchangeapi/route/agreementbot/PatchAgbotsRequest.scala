package org.openhorizon.exchangeapi.route.agreementbot

import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}
import slick.jdbc.PostgresProfile.api._

final case class PatchAgbotsRequest(token: Option[String], name: Option[String], msgEndPoint: Option[String], publicKey: Option[String]) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    if (token.isDefined && token.get == "") Option(ExchMsg.translate("token.cannot.be.empty.string"))
    // if (token.isDefined && !NodeAgbotTokenValidation.isValid(token.get)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    //else if (!requestBody.trim.startsWith("{") && !requestBody.trim.endsWith("}")) Some(ExchMsg.translate("invalid.input.message", requestBody))
    else None
  }

  /** Returns a tuple of the db action to update parts of the agbot, and the attribute name being updated. */
  def getDbUpdate(id: String, orgid: String, hashedTok: String): (DBIO[_],String) = {
    val lastHeartbeat: String = ApiTime.nowUTC
    //somday: support updating more than 1 attribute
    // find the 1st attribute that was specified in the body and create a db action to update it for this agbot
    token match {
      case Some(_) =>
        //val tok = if (Password.isHashed(token2)) token2 else Password.hash(token2)
        return ((for { d <- AgbotsTQ if d.id === id } yield (d.id,d.token,d.lastHeartbeat)).update((id, hashedTok, lastHeartbeat)), "token")
      case _ => ;
    }
    name match { case Some(name2) => return ((for { d <- AgbotsTQ if d.id === id } yield (d.id,d.name,d.lastHeartbeat)).update((id, name2, lastHeartbeat)), "name"); case _ => ; }
    msgEndPoint match { case Some(msgEndPoint2) => return ((for { d <- AgbotsTQ if d.id === id } yield (d.id,d.msgEndPoint,d.lastHeartbeat)).update((id, msgEndPoint2, lastHeartbeat)), "msgEndPoint"); case _ => ; }
    publicKey match { case Some(publicKey2) => return ((for { d <- AgbotsTQ if d.id === id } yield (d.id,d.publicKey,d.lastHeartbeat)).update((id, publicKey2, lastHeartbeat)), "publicKey"); case _ => ; }
    (null, null)
  }
}
