package com.horizon.exchangeapi.tables

import com.horizon.exchangeapi.ApiTime
import slick.jdbc.PostgresProfile.api._
import scala.collection.mutable.ListBuffer
import akka.event.LoggingAdapter


/** Stores the current DB schema version, and includes methods to upgrade to the latest schema. */

final case class SchemaRow(id: Int, schemaVersion: Int, description: String, lastUpdated: String) {
  //protected implicit val jsonFormats: Formats = DefaultFormats

  def toSchema: Schema = Schema(id, schemaVersion, description, lastUpdated)

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

  // Returns the db actions necessary to get the schema from step-1 to step. The fromSchemaVersion arg is there because sometimes where you
  // originally came from affects how to get to the next step.
  def getUpgradeSchemaStep(fromSchemaVersion: Int, step: Int)(implicit logger: LoggingAdapter): DBIO[_] = {
    step match {
      case 0 => DBIO.seq()       // v1.35.0 - no changes needed to get to time zero
      case 1 => DBIO.seq(NodeStatusTQ.rows.schema.create)    // v1.37.0
      case 2 => DBIO.seq(sqlu"alter table agbots drop column patterns", AgbotPatternsTQ.rows.schema.create)   // v1.38.0
      case 3 => DBIO.seq(ServicesTQ.rows.schema.create)   // v1.45.0
      case 4 => DBIO.seq(/*WorkloadKeysTQ.rows.schema.create, MicroserviceKeysTQ.rows.schema.create, ServiceKeysTQ.rows.schema.create,*/ PatternKeysTQ.rows.schema.create)   // v1.46.0
      case 5 => DBIO.seq(sqlu"alter table patterns add column services character varying not null default ''")   // v1.47.0
      case 6 => DBIO.seq(sqlu"alter table nodes add column regservices character varying not null default ''")   // v1.47.0
      case 7 => DBIO.seq(   // v1.47.0
          sqlu"alter table nodeagreements add column services character varying not null default ''",
          sqlu"alter table nodeagreements add column agrsvcorgid character varying not null default ''",
          sqlu"alter table nodeagreements add column agrsvcpattern character varying not null default ''",
          sqlu"alter table nodeagreements add column agrsvcurl character varying not null default ''"
        )
      case 8 => val actions = ListBuffer[DBIO[_]]()        // v1.48.0
        actions += sqlu"alter table agbotagreements add column serviceOrgid character varying not null default ''"
        actions += sqlu"alter table agbotagreements add column servicePattern character varying not null default ''"
        actions += sqlu"alter table agbotagreements add column serviceUrl character varying not null default ''"
        actions += sqlu"alter table nodestatus add column services character varying not null default ''"
        // If in this current level of code we started upgrading from 2 or less, that means we created the services table with the correct schema, so no need to modify it
        if (fromSchemaVersion >= 3) actions += sqlu"alter table services rename column pkg to imagestore"
        DBIO.seq(actions: _*)      // convert the list of actions to a DBIO seq
      case 9 => DBIO.seq(ServiceDockAuthsTQ.rows.schema.create)   // v1.52.0
      case 10 => DBIO.seq(   // v1.56.0
        sqlu"alter table agbotpatterns add column nodeorgid character varying not null default ''"
      )
      case 11 => DBIO.seq(   // v1.56.0
        sqlu"alter table servicedockauths add column username character varying not null default ''"
      )
      case 12 => DBIO.seq(   // v1.62.0
        sqlu"alter table patterns drop column workloads",
        sqlu"alter table agbotagreements drop column workloadorgid",
        sqlu"alter table agbotagreements drop column workloadpattern",
        sqlu"alter table agbotagreements drop column workloadurl",
        sqlu"alter table nodestatus drop column microservice",
        sqlu"alter table nodestatus drop column workloads",
        sqlu"alter table nodeagreements drop column microservice",
        sqlu"alter table nodeagreements drop column workloadorgid",
        sqlu"alter table nodeagreements drop column workloadpattern",
        sqlu"alter table nodeagreements drop column workloadurl",
        sqlu"drop table if exists properties", sqlu"drop table if exists nodemicros",
        sqlu"drop table if exists microservicekeys", sqlu"drop table if exists microservices",
        sqlu"drop table if exists workloadkeys", sqlu"drop table if exists workloads",
        sqlu"drop table if exists blockchains", sqlu"drop table if exists bctypes"
      )
      case 13 => DBIO.seq(   // v1.63.0
        sqlu"alter table services add column documentation character varying not null default ''",
        //ResourcesTQ.rows.schema.create,
        //ResourceKeysTQ.rows.schema.create,
        //ResourceAuthsTQ.rows.schema.create,
        sqlu"alter table services add column requiredResources character varying not null default ''"
      )
      case 14 => DBIO.seq(   // version 1.64.0
        sqlu"alter table orgs add column tags jsonb",
        sqlu"create index on orgs((tags->>'ibmcloud_id'))"
      )
      case 15 => DBIO.seq(   // version 1.65.0 ?
        sqlu"drop index orgs_expr_idx",
        sqlu"create unique index orgs_ibmcloud_idx on orgs((tags->>'ibmcloud_id'))"
      )
      case 16 => DBIO.seq(   // version 1.67.0
        sqlu"drop index orgs_ibmcloud_idx"
      )
      case 17 => DBIO.seq(   // version 1.72.0
        sqlu"alter table orgs add column orgtype character varying not null default ''"
      )
      case 18 => DBIO.seq(NodePolicyTQ.rows.schema.create)    // v1.77.0
      case 19 => DBIO.seq(ServicePolicyTQ.rows.schema.create)    // v1.77.0
      case 20 => DBIO.seq(BusinessPoliciesTQ.rows.schema.create)    // v1.78.0
      case 21 => DBIO.seq(    // v1.82.0
        sqlu"alter table services drop column requiredresources",
        sqlu"drop table if exists resourcekeys",
        sqlu"drop table if exists resourceauths",
        sqlu"drop table if exists resources"
      )
      case 22 => DBIO.seq(AgbotBusinessPolsTQ.rows.schema.create)    // v1.82.0
      case 23 => DBIO.seq(   // version 1.83.0
        sqlu"alter table nodes add column arch character varying not null default ''"
      )
      case 24 => DBIO.seq(   // version 1.84.0
        sqlu"alter table patterns add column userinput character varying not null default ''",
        sqlu"alter table businesspolicies add column userinput character varying not null default ''"
      )
      case 25 => DBIO.seq(   // version 1.87.0
        sqlu"alter table nodes add column userinput character varying not null default ''"
      )
      case 26 => DBIO.seq(   // version 1.92.0
        sqlu"alter table users add column updatedBy character varying not null default ''"
      )
      case 27 => DBIO.seq(NodeErrorTQ.rows.schema.create)    // v1.102.0
      case 28 => DBIO.seq(   // version 1.92.0
        sqlu"alter table nodestatus add column runningServices character varying not null default ''"
      )
      case 29 => DBIO.seq(ResourceChangesTQ.rows.schema.create)   // v1.122.0
      case 30 => DBIO.seq(   // v2.1.0
        sqlu"alter table nodes add column heartbeatintervals character varying not null default ''",
        sqlu"alter table orgs add column heartbeatintervals character varying not null default ''"
      )
      case 31 => DBIO.seq(   // v2.12.0
        sqlu"create index org_index on resourcechanges (orgid)",
        sqlu"create index id_index on resourcechanges (id)",
        sqlu"create index cat_index on resourcechanges (category)",
        sqlu"create index pub_index on resourcechanges (public)"
      )
      case 32 => DBIO.seq(   // v2.13.0
        sqlu"alter table nodes add column lastupdated character varying not null default ''"
      )
      case 33 => DBIO.seq(   // v2.14.0
        sqlu"alter table nodes add column nodetype character varying not null default ''",
        sqlu"alter table services add column clusterdeployment character varying not null default ''",
        sqlu"alter table services add column clusterdeploymentsignature character varying not null default ''"
      )
      case 34 => DBIO.seq(   // v2.14.0
        sqlu"alter table resourcechanges alter column changeid type bigint"
      )
      // NODE: IF ADDING A TABLE, DO NOT FORGET TO ALSO ADD IT TO ExchangeApiTables.initDB and dropDB
      case other => logger.error("getUpgradeSchemaStep was given invalid step "+other); DBIO.seq()   // should never get here
    }
  }
  val latestSchemaVersion = 34    // NOTE: THIS MUST BE CHANGED WHEN YOU ADD TO getUpgradeSchemaStep() above
  val latestSchemaDescription = "changeid in resourcechanges table now biging/scala long"
  // Note: if you need to manually set the schema number in the db lower: update schema set schemaversion = 12 where id = 0;

  def isLatestSchemaVersion(fromSchemaVersion: Int) = fromSchemaVersion >= latestSchemaVersion

  val rows = TableQuery[SchemaTable]

  def getSchemaRow = rows.filter(_.id === 0)
  def getSchemaVersion = rows.filter(_.id === 0).map(_.schemaversion)

  // Returns a sequence of DBIOActions that will upgrade the DB schema from fromSchemaVersion to the latest schema version.
  // It is assumed that this sequence of DBIOActions will be run transactionally.
  def getUpgradeActionsFrom(fromSchemaVersion: Int)(implicit logger: LoggingAdapter): DBIO[_] = {
    if (isLatestSchemaVersion(fromSchemaVersion)) {   // nothing to do
      logger.debug("Already at latest DB schema version - nothing to do")
      return DBIO.seq()
    }
    val actions = ListBuffer[DBIO[_]]()
    for (i <- (fromSchemaVersion+1) to latestSchemaVersion) {
      logger.debug("Adding DB schema upgrade actions to get from schema version "+(i-1)+" to "+i)
      //actions += upgradeSchemaVector(i)
      actions += getUpgradeSchemaStep(fromSchemaVersion, i)
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
final case class Schema(id: Int, schemaVersion: Int, description: String, lastUpdated: String)


// Test table
final case class FooRow(bar: String, description: String)
class FooTable(tag: Tag) extends Table[FooRow](tag, "foo") {
  def bar = column[String]("bar", O.PrimaryKey)
  def description = column[String]("description")
  def * = (bar, description) <> (FooRow.tupled, FooRow.unapply)
}
object FooTQ { val rows = TableQuery[FooTable] }
