package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.{ApiTime, ExchMsg}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class ChangePwRequest(newPassword: String) {
  require(newPassword!=null)
  def getAnyProblem: Option[String] = {
    if (newPassword == "") Option(ExchMsg.translate("password.cannot.be.set.to.empty.string"))
    else None // None means no problems with input
  }

  def getDbUpdate(username: String, orgid: String, hashedPw: String): DBIO[_] = {
    val lastUpdated: String = ApiTime.nowUTC
    //val pw = if (Password.isHashed(newPassword)) newPassword else Password.hash(newPassword)
    (for { u <- UsersTQ if u.username === username } yield (u.username, u.password, u.lastUpdated)).update((username, hashedPw, lastUpdated))
  }
}
