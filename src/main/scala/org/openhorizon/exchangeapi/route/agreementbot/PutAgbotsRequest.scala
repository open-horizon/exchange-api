package org.openhorizon.exchangeapi.route.agreementbot

import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.AgbotRow
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg}
import slick.jdbc.PostgresProfile.api._

/** Input format for PUT /orgs/{orgid}/agbots/<agbot-id> */
final case class PutAgbotsRequest(token: String, name: String, msgEndPoint: Option[String], publicKey: String) {
  require(token!=null && name!=null && publicKey!=null)
  protected implicit val jsonFormats: Formats = DefaultFormats
  def getAnyProblem: Option[String] = {
    if (token == "") Option(ExchMsg.translate("token.specified.cannot.be.blank"))
    // if (!NodeAgbotTokenValidation.isValid(token)) {
    //   if (ExchMsg.getLang.contains("ja") || ExchMsg.getLang.contains("ko") || ExchMsg.getLang.contains("zh")) return Some(ExchMsg.translate("invalid.password.i18n"))
    //   else return Some(ExchMsg.translate("invalid.password"))
    // }
    else None
  }

  /** Get the db queries to insert or update the agbot */
  def getDbUpsert(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = AgbotRow(id, orgid, hashedTok, name, owner, msgEndPoint.getOrElse(""), ApiTime.nowUTC, publicKey).upsert

  /** Get the db queries to update the agbot */
  def getDbUpdate(id: String, orgid: String, owner: String, hashedTok: String): DBIO[_] = AgbotRow(id, orgid, hashedTok, name, owner, msgEndPoint.getOrElse(""), ApiTime.nowUTC, publicKey).update
}
