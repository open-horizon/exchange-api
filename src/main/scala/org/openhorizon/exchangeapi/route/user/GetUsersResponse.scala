package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.table.user.User

final case class GetUsersResponse(users: Map[String, User] = Map.empty[String, User],
                                  lastIndex: Int = 0)
