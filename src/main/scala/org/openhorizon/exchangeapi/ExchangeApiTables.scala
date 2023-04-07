package org.openhorizon.exchangeapi

import akka.event.LoggingAdapter
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.ExecutionContext

import org.openhorizon.exchangeapi.table._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

/** The umbrella class for the DB tables. The specific table classes are in the tables subdir. */
object ExchangeApiTables {

  // Create all of the current version's tables - used in /admin/initdb
  val initDB = DBIO.seq(
    (
      SchemaTQ.schema
      ++ OrgsTQ.schema
      ++ UsersTQ.schema
      ++ ResourceChangesTQ.schema
      ++ NodesTQ.schema
      ++ NodeAgreementsTQ.schema
      ++ NodeStatusTQ.schema
      ++ NodeErrorTQ.schema
      ++ NodePolicyTQ.schema
      ++ AgbotsTQ.schema
      ++ AgbotAgreementsTQ.schema
      ++ AgbotPatternsTQ.schema
      ++ AgbotBusinessPolsTQ.schema
      ++ NodeMsgsTQ.schema
      ++ AgbotMsgsTQ.schema
      ++ ServicesTQ.schema
      ++ ServiceKeysTQ.schema
      ++ ServiceDockAuthsTQ.schema
      ++ ServicePolicyTQ.schema
      ++ PatternsTQ.schema
      ++ PatternKeysTQ.schema
      ++ BusinessPoliciesTQ.schema
      ++ SearchOffsetPolicyTQ.schema
      ++ ManagementPoliciesTQ.schema
      ++ NodeMgmtPolStatuses.schema
      ++ AgentCertificateVersionsTQ.schema
      ++ AgentConfigurationVersionsTQ.schema
      ++ AgentSoftwareVersionsTQ.schema
      ++ AgentVersionsChangedTQ.schema
      ++ NodeGroupTQ.schema
      ++ NodeGroupAssignmentTQ.schema
    ).create,
    SchemaTQ.getSetVersionAction)

  // Delete all of the current tables - the tables that are depended on need to be last in this list - used in /admin/dropdb
  // Note: doing this with raw sql stmts because a foreign key constraint not existing was causing slick's drops to fail. As long as we are not removing contraints (only adding), we should be ok with the drops below?
  //val delete = DBIO.seq(sqlu"drop table orgs", sqlu"drop table workloads", sqlu"drop table mmicroservices", sqlu"drop table blockchains", sqlu"drop table bctypes", sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table nodes", sqlu"drop table users")
  val dropDB = DBIO.seq(
    /* these are no longer used, but just in case they are still here */
    sqlu"DROP TABLE IF EXISTS public.node_group_assignment",
    sqlu"DROP TABLE IF EXISTS public.node_group",
    sqlu"DROP TABLE IF EXISTS public.agent_version_certificate",
    sqlu"DROP TABLE IF EXISTS public.agent_version_configuration",
    sqlu"DROP TABLE IF EXISTS public.agent_version_software",
    sqlu"DROP TABLE IF EXISTS public.agent_version_last_updated",
    sqlu"DROP TABLE IF EXISTS public.resourcekeys",
    sqlu"DROP TABLE IF EXISTS public.resourceauths",
    sqlu"DROP TABLE IF EXISTS public.resources",
    sqlu"DROP TABLE IF EXISTS public.management_policy_status_node",
    sqlu"DROP TABLE IF EXISTS public.managementpolicies CASCADE",
    sqlu"DROP TABLE IF EXISTS public.search_offset_policy CASCADE",
    sqlu"DROP TABLE IF EXISTS public.businesspolicies CASCADE",
    sqlu"DROP TABLE IF EXISTS public.patternkeys CASCADE",
    sqlu"DROP TABLE IF EXISTS public.patterns CASCADE",
    sqlu"DROP TABLE IF EXISTS public.servicepolicies CASCADE",
    sqlu"DROP TABLE IF EXISTS public.servicedockauths CASCADE",
    sqlu"DROP TABLE IF EXISTS public.servicekeys CASCADE",
    sqlu"DROP TABLE IF EXISTS public.services CASCADE", // no table depends on these
    sqlu"DROP TABLE IF EXISTS public.nodemsgs CASCADE",
    sqlu"DROP TABLE IF EXISTS public.agbotmsgs CASCADE", // these depend on both nodes and agbots
    sqlu"DROP TABLE IF EXISTS public.agbotbusinesspols CASCADE",
    sqlu"DROP TABLE IF EXISTS public.agbotpatterns CASCADE",
    sqlu"DROP TABLE IF EXISTS public.agbotagreements CASCADE",
    sqlu"DROP TABLE IF EXISTS public.agbots CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodeagreements CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodestatus CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodeerror CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodepolicies CASCADE",
    /* these are no longer used, but just in case they are still there */
    sqlu"DROP TABLE IF EXISTS public.properties CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodemicros CASCADE",
    sqlu"DROP TABLE IF EXISTS public.nodes CASCADE",
    sqlu"DROP TABLE IF EXISTS public.resourcechanges CASCADE",
    sqlu"DROP TABLE IF EXISTS public.users CASCADE",
    sqlu"DROP TABLE IF EXISTS public.orgs CASCADE",
    sqlu"DROP TABLE IF EXISTS public.schema CASCADE",
    // these are no longer used, but here just in case they are still hanging around
    sqlu"DROP TABLE IF EXISTS public.microservicekeys CASCADE",
    sqlu"DROP TABLE IF EXISTS public.microservices CASCADE",
    sqlu"DROP TABLE IF EXISTS public.workloadkeys CASCADE",
    sqlu"DROP TABLE IF EXISTS public.workloads CASCADE",
    sqlu"DROP TABLE IF EXISTS public.blockchains CASCADE",
    sqlu"DROP TABLE IF EXISTS public.bctypes CASCADE")

  /** Upgrades the db schema, or inits the db if necessary. Called every start up. */
  def upgradeDb(db: Database)(implicit logger: LoggingAdapter, executionContext: ExecutionContext): Unit = {
    // Run this and wait for it, because we don't want any other initialization occurring until the db is right
    val upgradeNotNeededMsg = "DB schema does not need upgrading, it is already at the latest schema version: "

    // The timeout exception that this can throw is handled by the caller of upgradeDb()
    val upgradeResult: ApiResponse = Await.result(db.run(SchemaTQ.getSchemaRow.result.asTry.flatMap({ xs =>
      logger.debug("ExchangeApiTables.upgradeDb current schema result: " + xs.toString)
      xs match {
        case Success(v) =>
          if (v.nonEmpty) {
            val schemaRow: SchemaRow = v.head
          
            if (SchemaTQ.isLatestSchemaVersion(schemaRow.schemaVersion))
              DBIO.failed(new Throwable(upgradeNotNeededMsg + schemaRow.schemaVersion)).asTry // db already at latest schema. I do not think there is a way to pass a msg thru the Success path
            else {
              logger.info("DB exists, but not at the current schema version. Upgrading the DB schema...")
              SchemaTQ.getUpgradeActionsFrom(schemaRow.schemaVersion)(logger).transactionally.asTry
            }
          }
          else {
            logger.debug("ExchangeApiTables.upgradeDb: success v was empty")
            DBIO.failed(new Throwable(ExchMsg.translate("db.upgrade.error"))).asTry
          }
        case Failure(t) =>
          if (t.getMessage.contains("""relation "schema" does not exist""")) {
            logger.info("Schema table does not exist, initializing the DB...")
            initDB.transactionally.asTry
          } // init the db
          else
            DBIO.failed(t).asTry // rethrow the error to the next step
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
