package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.utility.StrConstants
import org.openhorizon.exchangeapi.table.apikey.ApiKeyMetadata

final case class User(password: String,
                      admin: Boolean,
                      hubAdmin: Boolean,
                      email: String,
                      lastUpdated: String,
                      updatedBy: String,
                      apikeys: Option[Seq[ApiKeyMetadata]] = None ) {
  def hidePassword: User = User(StrConstants.hiddenPw, admin, hubAdmin, email, lastUpdated, updatedBy,apikeys)
}
