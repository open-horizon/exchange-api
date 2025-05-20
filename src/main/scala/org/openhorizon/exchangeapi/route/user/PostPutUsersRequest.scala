package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.auth.Identity
import org.openhorizon.exchangeapi.utility.ExchMsg

final case class PostPutUsersRequest(password: String,
                                     admin: Boolean = false,
                                     hubAdmin: Option[Boolean] = None,
                                     email: String)
