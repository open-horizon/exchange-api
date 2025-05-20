package org.openhorizon.exchangeapi.table.user

import org.openhorizon.exchangeapi.utility.ApiTime
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

import java.time.Instant
import scala.jdk.OptionConverters._
import java.util.UUID

case class UserRow(createdAt: java.sql.Timestamp,
                   email: Option[String] = None,
                   identityProvider: String = "Open Horizon",
                   isHubAdmin: Boolean = false,
                   isOrgAdmin: Boolean = false,
                   modifiedAt: java.sql.Timestamp,
                   modified_by: Option[UUID] = None,
                   organization: String,
                   password: Option[String] = None,
                   user: UUID = UUID.randomUUID(),
                   username: String) {
  def this(tuple: (java.sql.Timestamp,
                   Option[String],
                   String,
                   Boolean,
                   Boolean,
                   java.sql.Timestamp,
                   Option[UUID],
                   String,
                   UUID,
                   String)) =
       this(createdAt = tuple._1,
            email = tuple._2,
            identityProvider = tuple._3,
            isHubAdmin = tuple._4,
            isOrgAdmin = tuple._5,
            modifiedAt = tuple._6,
            modified_by = tuple._7,
            organization = tuple._8,
            user = tuple._9,
            username = tuple._10)
}
