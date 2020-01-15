package com.horizon.exchangeapi

import akka.event.LoggingAdapter
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

//import scala.concurrent.ExecutionContext.Implicits.global
import com.horizon.exchangeapi.tables._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

/** The umbrella class for the DB tables. The specific table classes are in the tables subdir. */
object ExchangeApiTables {

  // Create all of the current version's tables - used in /admin/initdb
  val initDB = DBIO.seq(
    (
      SchemaTQ.rows.schema ++ OrgsTQ.rows.schema ++ UsersTQ.rows.schema ++ ResourceChangesTQ.rows.schema
      ++ NodesTQ.rows.schema ++ NodeAgreementsTQ.rows.schema ++ NodeStatusTQ.rows.schema ++ NodeErrorTQ.rows.schema ++ NodePolicyTQ.rows.schema
      ++ AgbotsTQ.rows.schema ++ AgbotAgreementsTQ.rows.schema ++ AgbotPatternsTQ.rows.schema ++ AgbotBusinessPolsTQ.rows.schema
      ++ NodeMsgsTQ.rows.schema ++ AgbotMsgsTQ.rows.schema
      ++ ServicesTQ.rows.schema ++ ServiceKeysTQ.rows.schema ++ ServiceDockAuthsTQ.rows.schema ++ ServicePolicyTQ.rows.schema ++ PatternsTQ.rows.schema ++ PatternKeysTQ.rows.schema ++ BusinessPoliciesTQ.rows.schema).create,
    SchemaTQ.getSetVersionAction)

  // Delete all of the current tables - the tables that are depended on need to be last in this list - used in /admin/dropdb
  // Note: doing this with raw sql stmts because a foreign key constraint not existing was causing slick's drops to fail. As long as we are not removing contraints (only adding), we should be ok with the drops below?
  //val delete = DBIO.seq(sqlu"drop table orgs", sqlu"drop table workloads", sqlu"drop table mmicroservices", sqlu"drop table blockchains", sqlu"drop table bctypes", sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table nodes", sqlu"drop table users")
  val dropDB = DBIO.seq(
    /* these are no longer used, but just in case they are still here */ sqlu"drop table if exists resourcekeys", sqlu"drop table if exists resourceauths", sqlu"drop table if exists resources",
    sqlu"drop table if exists businesspolicies", sqlu"drop table if exists patternkeys", sqlu"drop table if exists patterns", sqlu"drop table if exists servicepolicies", sqlu"drop table if exists servicedockauths", sqlu"drop table if exists servicekeys", sqlu"drop table if exists services", // no table depends on these
    sqlu"drop table if exists nodemsgs", sqlu"drop table if exists agbotmsgs", // these depend on both nodes and agbots
    sqlu"drop table if exists agbotbusinesspols", sqlu"drop table if exists agbotpatterns", sqlu"drop table if exists agbotagreements", sqlu"drop table if exists agbots",
    sqlu"drop table if exists nodeagreements", sqlu"drop table if exists nodestatus", sqlu"drop table if exists nodeerror", sqlu"drop table if exists nodepolicies",
    /* these are no longer used, but just in case they are still there */ sqlu"drop table if exists properties", sqlu"drop table if exists nodemicros",
    sqlu"drop table if exists nodes", sqlu"drop table if exists resourcechanges",
    sqlu"drop table if exists users", sqlu"drop table if exists orgs", sqlu"drop table if exists schema",
    // these are no longer used, but here just in case they are still hanging around
    sqlu"drop table if exists microservicekeys", sqlu"drop table if exists microservices", sqlu"drop table if exists workloadkeys", sqlu"drop table if exists workloads", sqlu"drop table if exists blockchains", sqlu"drop table if exists bctypes")

  /** Upgrades the db schema, or inits the db if necessary. Called every start up. */
  def upgradeDb(db: Database)(implicit logger: LoggingAdapter, executionContext: ExecutionContext): Unit = {
    //val logger = LoggerFactory.getLogger(ExchConfig.LOGGER)

    // Run this and wait for it, because we don't want any other initialization occurring until the db is right
    val upgradeNotNeededMsg = "DB schema does not need upgrading, it is already at the latest schema version: "

    // The timeout exception that this can throw is handled by the caller of upgradeDb()
    val upgradeResult = Await.result(db.run(SchemaTQ.getSchemaRow.result.asTry.flatMap({ xs =>
      logger.debug("ExchangeApiTables.upgradeDb current schema result: " + xs.toString)
      xs match {
        case Success(v) => if (v.nonEmpty) {
          val schemaRow = v.head
          if (SchemaTQ.isLatestSchemaVersion(schemaRow.schemaVersion)) DBIO.failed(new Throwable(upgradeNotNeededMsg + schemaRow.schemaVersion)).asTry // db already at latest schema. I do not think there is a way to pass a msg thru the Success path
          else {
            logger.info("DB exists, but not at the current schema version. Upgrading the DB schema...")
            SchemaTQ.getUpgradeActionsFrom(schemaRow.schemaVersion)(logger).transactionally.asTry
          }
        } else {
          logger.debug("ExchangeApiTables.upgradeDb: success v was empty")
          DBIO.failed(new Throwable(ExchMsg.translate("db.upgrade.error"))).asTry
        }
        case Failure(t) => if (t.getMessage.contains("""relation "schema" does not exist""")) {
          logger.info("Schema table does not exist, initializing the DB...")
          initDB.transactionally.asTry
        } // init the db
        else DBIO.failed(t).asTry // rethrow the error to the next step
      }
    })).map({ xs =>
      logger.debug("ExchangeApiTables.upgradeDb: processing upgrade check, upgrade, or init db result") // dont want to display xs.toString because it will have a scary looking error in it in the case of the db already being at the latest schema
      xs match {
        case Success(_) => ApiResponse(ApiRespType.OK, ExchMsg.translate("db.upgraded.successfully")) // cant tell the diff between these 2, they both return Success(())
        case Failure(t) => if (t.getMessage.contains(upgradeNotNeededMsg)) ApiResponse(ApiRespType.OK, t.getMessage) // db already at latest schema
        else ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.upgraded", t.toString)) // we hit some problem
      }
    }), Duration(ExchConfig.getInt("api.db.upgradeTimeoutSeconds"), SECONDS)) // this is the rest of Await.result(), wait 1 minute for db init/upgrade to complete
    if (upgradeResult.code == ApiRespType.OK) logger.info(upgradeResult.msg)
    else logger.error("ERROR: failure to init or upgrade db: " + upgradeResult.msg)
  }

}
