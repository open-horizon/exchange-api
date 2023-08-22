package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.utility.StrConstants

final case class User(password: String,
                      admin: Boolean,
                      hubAdmin: Boolean,
                      email: String,
                      lastUpdated: String,
                      updatedBy: String) {
  def hidePassword: User = User(StrConstants.hiddenPw, admin, hubAdmin, email, lastUpdated, updatedBy)
}
