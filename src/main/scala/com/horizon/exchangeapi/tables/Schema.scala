package com.horizon.exchangeapi.tables

//import org.json4s._
//import org.json4s.jackson.Serialization.read
import com.horizon.exchangeapi.ApiTime
import slick.jdbc.PostgresProfile.api._
import org.slf4j._
import scala.collection.mutable.ListBuffer


/** Stores the current DB schema version, and includes methods to upgrade to the latest schema. */

case class SchemaRow(id: Int, schemaVersion: Int, description: String, lastUpdated: String) {
  //protected implicit val jsonFormats: Formats = DefaultFormats

  def toSchema: Schema = new Schema(id, schemaVersion, description, lastUpdated)

  // update returns a DB action to update this row
  def update: DBIO[_] = (for {m <- SchemaTQ.rows if m.id === 0 } yield m).update(this)

  // insert returns a DB action to insert this row
  def insert: DBIO[_] = SchemaTQ.rows += this

  // Returns a DB action to insert or update this row
  def upsert: DBIO[_] = SchemaTQ.rows.insertOrUpdate(this)
}

/** Mapping of the schemas db table to a scala class */
class SchemaTable(tag: Tag) extends Table[SchemaRow](tag, "schema") {
  def id = column[Int]("id", O.PrimaryKey)      // we only have 1 row, so this is always 0
  def schemaversion = column[Int]("schemaversion")
  def description = column[String]("description")
  def lastUpdated = column[String]("lastupdated")
  // this describes what you get back when you return rows from a query
  def * = (id, schemaversion, description, lastUpdated) <> (SchemaRow.tupled, SchemaRow.unapply)
}

// Instance to access the schemas table
object SchemaTQ {
  def latestSchemaVersion = upgradeSchemaVector.size - 1

  // Each index in this vector contains the db schema upgrade actions to get from (index-1) version to (index) version
  val upgradeSchemaVector = Vector(
    /* 0 */ DBIO.seq(),       // v1.35.0 - no changes needed to get to time zero
    /* 1 */ DBIO.seq(NodeStatusTQ.rows.schema.create),    // v1.37.0
    /* 2 */ DBIO.seq(sqlu"alter table agbots drop column patterns", AgbotPatternsTQ.rows.schema.create),   // v1.38.0
    /* 3 */ DBIO.seq(ServicesTQ.rows.schema.create),   // v1.45.0
    /* 4 */ DBIO.seq(WorkloadKeysTQ.rows.schema.create, MicroserviceKeysTQ.rows.schema.create, ServiceKeysTQ.rows.schema.create, PatternKeysTQ.rows.schema.create)   // v1.46.0
  )
  val latestSchemaDescription = "Added workloadkeys, microservicekeys, servicekeys, patternkeys tables"

  val rows = TableQuery[SchemaTable]

  def getSchemaRow = rows.filter(_.id === 0)
  def getSchemaVersion = rows.filter(_.id === 0).map(_.schemaversion)

  // Returns a sequence of DBIOActions that will upgrade the DB schema from fromSchemaVersion to the latest schema version.
  // It is assumed that this sequence of DBIOActions will be run transactionally.
  def getUpgradeActionsFrom(fromSchemaVersion: Int)(implicit logger: Logger): DBIO[_] = {
    if (fromSchemaVersion >= latestSchemaVersion) {   // nothing to do
      logger.debug("Already at latest DB schema version - nothing to do")
      return DBIO.seq()
    }
    val actions = ListBuffer[DBIO[_]]()
    for (i <- (fromSchemaVersion+1) to latestSchemaVersion) {
      logger.debug("Adding DB schema upgrade actions to get from schema version "+(i-1)+" to "+i)
      actions += upgradeSchemaVector(i)
    }
    actions += getSetVersionAction    // record that we are at the latest schema version now
    return DBIO.seq(actions: _*)      // convert the list of actions to a DBIO seq
  }

  def getSetVersionAction: DBIO[_] = SchemaRow(0, latestSchemaVersion, latestSchemaDescription, ApiTime.nowUTC).upsert
  // Note: to manually change the schema version in the DB for testing: update schema set schemaversion=0;

  // Use this together with ExchangeApiTables.deleteNewTables to back out the latest schema update, so you can redo it
  def getDecrementVersionAction(currentSchemaVersion: Int): DBIO[_] = SchemaRow(0, currentSchemaVersion-1, "decremented schema version", ApiTime.nowUTC).upsert

  def getDeleteAction: DBIO[_] = getSchemaRow.delete
}

// This is the schema table minus the key - used as the data structure to return to the REST clients
class Schema(var id: Int, var schemaVersion: Int, var description: String, var lastUpdated: String) {
  //def copy = new Schema(label, description, lastUpdated)
}


// Test table
case class FooRow(bar: String, description: String)
class FooTable(tag: Tag) extends Table[FooRow](tag, "foo") {
  def bar = column[String]("bar", O.PrimaryKey)
  def description = column[String]("description")
  def * = (bar, description) <> (FooRow.tupled, FooRow.unapply)
}
object FooTQ { val rows = TableQuery[FooTable] }
