package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.StrConstants

import java.time.ZoneId
import java.util.UUID
import org.openhorizon.exchangeapi.table.apikey.ApiKeyMetadata

final case class User(admin: Boolean = false,
                      email: String = "",
                      hubAdmin: Boolean = false,
                      lastUpdated: String,
                      password: String = "",
                      updatedBy: String = "",
                      apikeys: Option[Seq[ApiKeyMetadata]] = None) {

  def this(tuple: (UserRow, Option[(String, UUID, String)]), apikeys: Option[Seq[ApiKeyMetadata]]) =
    this(
      admin = tuple._1.isOrgAdmin,
      hubAdmin = tuple._1.isHubAdmin,
      email = tuple._1.email.getOrElse(""),
      lastUpdated =
        fixFormatting(tuple._1.modifiedAt.toInstant
          .atZone(ZoneId.of("UTC"))
          .withZoneSameInstant(ZoneId.of("UTC"))
          .toString),
      password = tuple._1.password.getOrElse(""),
      updatedBy = tuple._2 match {
        case Some(user) => s"${user._1}/${user._3}"
        case _ => ""
      },
      apikeys = apikeys
    )
}
