package org.openhorizon.exchangeapi.route.user

import org.openhorizon.exchangeapi.table.User

final case class GetUsersResponse(users: Map[String, User],
                                  lastIndex: Int)
