package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.table.user.User

final case class GetUsersResponse(users: Map[String, User],
                                  lastIndex: Int)
