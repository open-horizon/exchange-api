package org.openhorizon.exchangeapi.route.user

/**
 * Json4s cannot serialize nested case class structures from Json to Scala. These test variants are standard classes.
 * We can get away with this as the content of these classes is not used in the runtime, and we are parsing
 * from Strings.
 */

class TestUser(val admin: Boolean = false,
               val email: String = "",
               val hubAdmin: Boolean = false,
               val lastUpdated: String,
               val password: String = "",
               val updatedBy: String = "")

class TestGetUsersResponse(val users: Map[String, TestUser] = Map.empty[String, TestUser],
                           val lastIndex: Int = 0)
