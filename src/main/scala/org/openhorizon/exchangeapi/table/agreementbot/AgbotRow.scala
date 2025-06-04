package org.openhorizon.exchangeapi.table.agreementbot

import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.Role
import org.openhorizon.exchangeapi.utility.StrConstants
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.util.UUID


final case class AgbotRow(id: String,
                          orgid: String,
                          token: String,
                          name: String,
                          owner: UUID,
                          /*patterns: String,*/
                          msgEndPoint: String,
                          lastHeartbeat: String,
                          publicKey: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def upsert: DBIO[_] = { //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.fastHash(token)  <- token is already hashed
    // If owner is root, do not set owner so we do not take over a user's agbot. It will default to root if upsert turns out to be a insert
    if (Role.isSuperUser(owner.toString)) AgbotsTQ.map(a => (a.id, a.orgid, a.token, a.name, /*a.patterns,*/ a.msgEndPoint, a.lastHeartbeat, a.publicKey)).insertOrUpdate((id, orgid, token, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey)) else AgbotsTQ.insertOrUpdate(AgbotRow(id, orgid, token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
  }
  
  def update: DBIO[_] = { //val tok = if (token == "") "" else if (Password.isHashed(token)) token else Password.fastHash(token)  <- token is already hashed
    if (false)
      (for {
        a <- AgbotsTQ if a.id === id
      } yield (a.id, a.orgid, a.token, a.name, /*a.patterns,*/ a.msgEndPoint, a.lastHeartbeat, a.publicKey)).update((id, orgid, token, name, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
    else
      (for {
        a <-
          AgbotsTQ.filter(_.id === id)
                  .map(agbots =>
                        (agbots.id,
                         agbots.orgid,
                         agbots.token,
                         agbots.name,
                         agbots.msgEndPoint,
                         agbots.lastHeartbeat,
                         agbots.publicKey))
      } yield a).update((id, orgid, token, name, /*owner, *//*patterns,*/ msgEndPoint, lastHeartbeat, publicKey))
  }
}
