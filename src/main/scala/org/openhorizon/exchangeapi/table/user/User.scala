package org.openhorizon.exchangeapi.table.user

import java.util.UUID
import org.openhorizon.exchangeapi.table.apikey.ApiKeyMetadata

final case class User(admin: Boolean = false,
                      apikeys: Option[Seq[ApiKeyMetadata]] = None,
                      email: String = "",
                      hubAdmin: Boolean = false,
                      lastUpdated: String,
                      password: String = "",
                      updatedBy: String = "") {

  def this(tuple: (UserRow, Option[(String, UUID, String)]), apikeys: Option[Seq[ApiKeyMetadata]]) =
    this(
      admin = tuple._1.isOrgAdmin,
      apikeys = apikeys,
      hubAdmin = tuple._1.isHubAdmin,
      email = tuple._1.email.getOrElse(""),
      lastUpdated = tuple._1.modifiedAt.toString,
      password = tuple._1.password.getOrElse(""),
      updatedBy = tuple._2 match {
        case Some(user) => s"${user._1}/${user._3}"
        case _ => ""
      }
    )
}
