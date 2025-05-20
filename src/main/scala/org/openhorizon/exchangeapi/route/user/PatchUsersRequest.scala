package org.openhorizon.exchangeapi.route.user

import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.auth.Identity
import org.openhorizon.exchangeapi.table.user.UsersTQ
import org.openhorizon.exchangeapi.utility.{ApiTime, ExchMsg}
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class PatchUsersRequest(password: Option[String] = None,
                                   admin: Option[Boolean] = None,
                                   hubAdmin: Option[Boolean] = None,
                                   email: Option[String] = None)
