package org.openhorizon.exchangeapi.table.schema

import slick.jdbc.PostgresProfile.api._


class SchemaTable(tag: Tag) extends Table[SchemaRow](tag, "schema") {
  def id = column[Int]("id", O.PrimaryKey)      // we only have 1 row, so this is always 0
  def schemaversion = column[Int]("schemaversion")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  
  def * = (id, schemaversion, description, lastUpdated).<>(SchemaRow.tupled, SchemaRow.unapply)
}
