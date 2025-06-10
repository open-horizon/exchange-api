package org.openhorizon.exchangeapi.table.resourcechange

import org.json4s.{DefaultFormats, Formats}
import slick.jdbc.PostgresProfile.api._
import slick.dbio.DBIO

final case class ResourceChangeRow(changeId: Long = 0L,
                                   orgId: String,
                                   id: String,
                                   category: String,
                                   public: String,
                                   resource: String,
                                   operation: String,
                                   lastUpdated: java.sql.Timestamp) {
  protected implicit val jsonFormats: Formats = DefaultFormats

  //def toResourceChange: ResourceChange = ResourceChange(changeId, orgId, id, category, public, resource, operation, lastUpdated)

  // update returns a DB action to update this row
  //def update: DBIO[_] = (for { m <- ResourceChangesTQ if m.changeId === changeId} yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ResourceChangesTQ += this

  // Returns a DB action to insert or update this row
  //def upsert: DBIO[_] = ResourceChangesTQ.insertOrUpdate(this)
}
