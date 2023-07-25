package org.openhorizon.exchangeapi.table.schema

import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

/** Stores the current DB schema version, and includes methods to upgrade to the latest schema. */

final case class SchemaRow(id: Int,
                           schemaVersion: Int,
                           description: String,
                           lastUpdated: String) {
  //protected implicit val jsonFormats: Formats = DefaultFormats

  def toSchema: Schema = Schema(id, schemaVersion, description, lastUpdated)

  // update returns a DB action to update this row
  def update: DBIO[_] = (for {m <- SchemaTQ if m.id === 0 } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = SchemaTQ += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = SchemaTQ.insertOrUpdate(this)
}
