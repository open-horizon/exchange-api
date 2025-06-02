package org.openhorizon.exchangeapi.table

import org.apache.pekko.event.LoggingAdapter
import org.json4s.{DefaultFormats, Formats, JObject, JString, JValue, _}
import org.json4s.native.JsonMethods._
import org.openhorizon.exchangeapi.ExchangeApiApp.{cacheResourceIdentity, system}
import org.openhorizon.exchangeapi.auth.{AuthCache, AuthRoles, Identity2, Password, Role}
import org.openhorizon.exchangeapi.table.agent.AgentVersionsChangedTQ
import org.openhorizon.exchangeapi.table.agent.certificate.AgentCertificateVersionsTQ
import org.openhorizon.exchangeapi.table.agent.configuration.AgentConfigurationVersionsTQ
import org.openhorizon.exchangeapi.table.agent.software.AgentSoftwareVersionsTQ
import org.openhorizon.exchangeapi.table.agreementbot.AgbotsTQ
import org.openhorizon.exchangeapi.table.agreementbot.agreement.AgbotAgreementsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern.AgbotPatternsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.AgbotBusinessPolsTQ
import org.openhorizon.exchangeapi.table.agreementbot.message.AgbotMsgsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.PatternsTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.key.PatternKeysTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.SearchOffsetPolicyTQ
import org.openhorizon.exchangeapi.table.ExchangePostgresProfile.api._
import org.openhorizon.exchangeapi.table.managementpolicy.ManagementPoliciesTQ
import org.openhorizon.exchangeapi.table.node.NodesTQ
import org.openhorizon.exchangeapi.table.node.agreement.NodeAgreementsTQ
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.NodePolicyTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.NodeMgmtPolStatuses
import org.openhorizon.exchangeapi.table.node.message.NodeMsgsTQ
import org.openhorizon.exchangeapi.table.node.status.NodeStatusTQ
import org.openhorizon.exchangeapi.table.organization.{OrgRow, OrgsTQ}
import org.openhorizon.exchangeapi.table.resourcechange.{ResChangeCategory, ResChangeOperation, ResChangeResource, ResourceChangeRow, ResourceChangesTQ}
import org.openhorizon.exchangeapi.table.schema.{SchemaRow, SchemaTQ}
import org.openhorizon.exchangeapi.table.service.dockerauth.ServiceDockAuthsTQ
import org.openhorizon.exchangeapi.table.service.key.ServiceKeysTQ
import org.openhorizon.exchangeapi.table.service.policy.ServicePolicyTQ
import org.openhorizon.exchangeapi.table.service.{SearchServiceTQ, ServicesTQ}
import org.openhorizon.exchangeapi.table.user.{UserRow, UsersTQ}
import org.openhorizon.exchangeapi.utility.ApiTime.fixFormatting
import org.openhorizon.exchangeapi.utility.{ApiRespType, ApiResponse, ApiTime, Configuration, ExchMsg}
import org.postgresql.util.PSQLException
import org.springframework.security.crypto.bcrypt.BCrypt
import scalacache.modes.scalaFuture.mode

import java.util.UUID
//import slick.jdbc.PostgresProfile.api._

import java.sql.Timestamp
import java.time.ZoneId
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Failure, Success}

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
      ++ SearchServiceTQ.schema
    ).create.transactionally,
    SchemaTQ.getSetVersionAction)

  // Delete all of the current tables - the tables that are depended on need to be last in this list - used in /admin/dropdb
  // Note: doing this with raw sql stmts because a foreign key constraint not existing was causing slick's drops to fail. As long as we are not removing contraints (only adding), we should be ok with the drops below?
  //val delete = DBIO.seq(sqlu"drop table orgs", sqlu"drop table workloads", sqlu"drop table mmicroservices", sqlu"drop table blockchains", sqlu"drop table bctypes", sqlu"drop table devmsgs", sqlu"drop table agbotmsgs", sqlu"drop table agbotagreements", sqlu"drop table agbots", sqlu"drop table devagreements", sqlu"drop table properties", sqlu"drop table microservices", sqlu"drop table nodes", sqlu"drop table users")
  val dropDB = DBIO.seq(
    /* these are no longer used, but just in case they are still here */
    sqlu"DROP TABLE IF EXISTS public.search_service",
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

  /** Create the root user in the DB. This is done separately from load() because we need the db execution context */
  def createRoot(db: Database)(implicit logger: LoggingAdapter, executionContext: ExecutionContext): Unit = {
    val changeTimestamp: Timestamp = ApiTime.nowUTCTimestamp
    val timeStamp: String = fixFormatting(changeTimestamp.toInstant.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.of("UTC")).toString)
    val configHubAdmins: scala.collection.mutable.Map[String, UserRow] = mutable.Map.empty[String, UserRow]
    
    // IBM Cloud Account ID for the root Organization
    val ibmCloudAcctID: Option[JValue] =
      try {
        Some(JObject("ibmcloud_id" -> JString(Configuration.getConfig.getString("api.root.account_id"))))
      }
      catch {
        case _: Exception => None
      }
    
    implicit val jsonFormats: Formats = DefaultFormats
    
    val InitialOrganizations =
      Seq(OrgRow(description = "Organization containing IBM services",
                 heartbeatIntervals = "",
                 label = "IBM Org",
                 lastUpdated = timeStamp,
                 limits = "",
                 orgId = "IBM",
                 orgType = "IBM",
                 tags = None),
          OrgRow(description = "Organization for the root user only",
                 heartbeatIntervals = "",
                 label = "Root Org",
                 lastUpdated = timeStamp,
                 limits = "",
                 orgId = "root",
                 orgType = "",
                 tags = ibmCloudAcctID))
    
    Configuration.getConfig.getObjectList("api.hubadmins").asScala.foreach({
      hubAdminConfigObject =>
        try {
          val organization = hubAdminConfigObject.toConfig.getString("org")
          val user = hubAdminConfigObject.toConfig.getString("user")
          val resource = organization + "/" + user
          
          if(organization.nonEmpty && organization == "root") {
            if(user.nonEmpty && user != "root") {
              configHubAdmins += (resource ->
                                   UserRow(createdAt = changeTimestamp,
                                           email = None,
                                           identityProvider = "Open Horizon",
                                           isHubAdmin = true,
                                           isOrgAdmin = false,
                                           modifiedAt = changeTimestamp,
                                           modified_by = None,
                                           organization = organization,
                                           password = Option(hubAdminConfigObject.toConfig.getString("password")),
                                           user = UUID.randomUUID(),
                                           username = user))
                   
            }
            else
              logger.error(s"Hub Admin user cannot be root")
          } else
            logger.error(s"Hub Admin user {} must be a member of the root organization", resource)
        }
        catch {
          case _: Exception =>
            logger.error(s"Hub Admin user not created from configuration.")
        }
    })
    
    // Root is disabled on type mismatches, null values, and explicit disables in config.
    val configRootPasswdHashed: Option[String] = {
      try {
        if(Configuration.getConfig.getBoolean("api.root.enabled"))
           Option(BCrypt.hashpw(Configuration.getConfig.getString("api.root.password"), BCrypt.gensalt(10)))
          // Option(Password.fastHash(Configuration.getConfig.getString("api.root.password")))
        else
          None
      }
      catch {
        case _: Exception => None
      }
    }
    val rootUser: UserRow =
      UserRow(createdAt = changeTimestamp,
              email = None,
              identityProvider = "Open Horizon",
              isHubAdmin = true,
              isOrgAdmin = true,
              modifiedAt = changeTimestamp,
              modified_by = None,
              organization = "root",
              password = configRootPasswdHashed,
              user = UUID.randomUUID(),
              username = "root")
    
    val something =
      for {
        numOrgsUpdated <-
          Compiled(OrgsTQ.filter(_.orgid === "root")
                         .map(organization => (organization.lastUpdated, organization.tags)))
                    .update((timeStamp, ibmCloudAcctID))
        
        organizationUpdated: Seq[ResourceChangeRow] =
          if(numOrgsUpdated.equals(1))
            Seq(ResourceChangeRow(category = ResChangeCategory.ORG.toString,
                                  changeId = 0L,
                                  id = "root",
                                  lastUpdated = changeTimestamp,
                                  operation = ResChangeOperation.MODIFIED.toString,
                                  orgId = "root",
                                  public = "false",
                                  resource = ResChangeResource.ORG.toString))
          else
            Seq.empty[ResourceChangeRow]
        
        requiredOrgs <-
          Compiled(OrgsTQ.filter(_.orgid inSet Seq("IBM", "root"))
                         .map(_.orgid))
                    .result
        
        createdOrgs <-
          (OrgsTQ returning OrgsTQ.map(_.orgid)) ++=
            InitialOrganizations.filterNot(organization =>
                                            requiredOrgs.nonEmpty &&
                                            requiredOrgs.contains(organization.orgId))
        
        organizationsCreated: Seq[ResourceChangeRow] =
          organizationUpdated :++ createdOrgs.map(
            organization =>
              (ResourceChangeRow(category = ResChangeCategory.ORG.toString,
                                 changeId = 0L,
                                 id = organization,
                                 lastUpdated = changeTimestamp,
                                 operation = ResChangeOperation.CREATED.toString,
                                 orgId = organization,
                                 public = "false",
                                 resource = ResChangeResource.ORG.toString))
          )
        
        _ <-
          ResourceChangesTQ ++= organizationsCreated
        
        numUsersUpdated <-
          Compiled(UsersTQ.filter(user => (user.organization === "root" &&
                                           user.username === "root"))
                          .map(user =>
                                (user.modifiedAt,
                                 user.password)))
                    .update((changeTimestamp,
                             configRootPasswdHashed))
        
        existingUsers <-
          Compiled(UsersTQ.filter(_.organization === "root")
                          .map(user => (user.organization ++ "/" ++ user.username)))
                    .result
        
        createdUsers <-
          (UsersTQ returning UsersTQ.map(users => (users.organization,users.username))) ++= {
            if (numUsersUpdated == 1)
              configHubAdmins.filterNot(hubadmin => existingUsers.contains(hubadmin._1)).values.toSeq
            else
              configHubAdmins.filterNot(hubadmin => existingUsers.contains(hubadmin._1)).values.toSeq :+
                rootUser
          }
        
        //_ = {
          //AuthCache.putUser(Role.superUser, configRootPasswdHashed.getOrElse(""), "")
          //for (user <- createdUsers.filterNot(_ == ("root","root"))) {
          //  AuthCache.putUser(user, configHubAdmins(user).password.getOrElse(""), "")
          //}
        //}
        
      } yield()
    
    db.run(something.transactionally.asTry).map({
      case Success(_) =>
        logger.info("Successfully updated/inserted root org, root user, IBM org, and hub admins from config")
        
        //if (configRootPasswdHashed.isDefined)
        //  cacheResourceIdentity.put("root/root")(value = (Identity2(identifier = Option(rootUser.user), organization = "root", owner = None, role = AuthRoles.SuperUser, username = "root"), rootUser.password.get), ttl = Option(Configuration.getConfig.getInt("api.cache.idsTtlSeconds").seconds))
        
      case Failure(t) =>
        logger.error(s"Failed to update/insert root org, root user, IBM org, and hub admins from config: ${t.toString}")
    })
  }
  
  /** Upgrades the db schema, or inits the db if necessary. Called every start up. */
  // The timeout exception that this can throw is handled by the caller of upgradeDb()
  def upgradeDb(db: Database)(implicit logger: LoggingAdapter, executionContext: ExecutionContext): Unit = {
    // Run this and wait for it, because we don't want any other initialization occurring until the db is right
    val upgradeNotNeededMsg = "DB schema does not need upgrading, it is already at the latest schema version: "
    
    val upgradeSchama =
      for {
        currentVersion <- Compiled(SchemaTQ.getSchemaRow).result
        
        _ <-
          if (currentVersion.isEmpty) {
            logger.debug("ExchangeApiTables.upgradeDb: current schema version was empty")
            DBIO.failed(new Throwable(ExchMsg.translate("db.upgrade.error")))
          }
          else
            DBIO.successful(())
        
        schemaRow: SchemaRow = currentVersion.head
        
        _ <-
          if (SchemaTQ.isLatestSchemaVersion(schemaRow.schemaVersion))
            DBIO.failed(new Throwable(upgradeNotNeededMsg + schemaRow.schemaVersion)) // db already at latest schema. I do not think there is a way to pass a msg thru the Success path
          else
            DBIO.successful(())
        
        schemaUpgrades <- {
          logger.info("DB exists, but not at the current schema version. Upgrading the DB schema...")
          SchemaTQ.getUpgradeActionsFrom(schemaRow.schemaVersion)(logger)
        }
      } yield()
    
    logger.debug("ExchangeApiTables.upgradeDb: processing upgrade check, upgrade, or init db result") // dont want to display xs.toString because it will have a scary looking error in it in the case of the db already being at the latest schema
    val upgradeResult: ApiResponse =
      Await.result(db.run(upgradeSchama.transactionally.asTry)
                     .map({
                        case Success(_) =>
                          ApiResponse(ApiRespType.OK, ExchMsg.translate("db.upgraded.successfully"))
                        case Failure(e: PSQLException) =>
                          if (e.getServerErrorMessage.getMessage == """relation "schema" does not exist""") {
                            logger.info("Schema table does not exist, initializing the DB...")
                            ApiResponse(ApiRespType.NOT_FOUND, ExchMsg.translate("not.found"))
                          }
                          else {
                            // A PLSQL error occured during the upgrade process. Rollback to schema version the Exchange booted with.
                            ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.upgraded", e.toString)) // we hit some problem
                          }
                        case Failure(t) =>
                          if (t.getMessage.contains(upgradeNotNeededMsg))
                            ApiResponse(ApiRespType.ALREADY_EXISTS, ExchMsg.translate("already.exists"))
                          else
                            ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.upgraded", t.toString))
                     }),
                   Duration(Configuration.getConfig.getInt("api.db.upgradeTimeoutSeconds"), SECONDS)) // this is the rest of Await.result(), wait 1 minute for db init/upgrade to complete
    
    
    val initializeSchema =
      for {
        _ <-
          (SchemaTQ.schema ++
           OrgsTQ.schema ++
           UsersTQ.schema ++
           ResourceChangesTQ.schema ++
           NodesTQ.schema ++
           NodeAgreementsTQ.schema ++
           NodeStatusTQ.schema ++
           NodeErrorTQ.schema ++
           NodePolicyTQ.schema ++
           AgbotsTQ.schema ++
           AgbotAgreementsTQ.schema ++
           AgbotPatternsTQ.schema ++
           AgbotBusinessPolsTQ.schema ++
           NodeMsgsTQ.schema ++
           AgbotMsgsTQ.schema ++
           ServicesTQ.schema ++
           ServiceKeysTQ.schema ++
           ServiceDockAuthsTQ.schema ++
           ServicePolicyTQ.schema ++
           PatternsTQ.schema ++
           PatternKeysTQ.schema ++
           BusinessPoliciesTQ.schema ++
           SearchOffsetPolicyTQ.schema ++
           ManagementPoliciesTQ.schema ++
           NodeMgmtPolStatuses.schema ++
           AgentCertificateVersionsTQ.schema ++
           AgentConfigurationVersionsTQ.schema ++
           AgentSoftwareVersionsTQ.schema ++
           AgentVersionsChangedTQ.schema ++
           NodeGroupTQ.schema ++
           NodeGroupAssignmentTQ.schema ++
           SearchServiceTQ.schema).create
        
          _ <- SchemaTQ.getSetVersionAction
      } yield()
    
    val initializeResult: ApiResponse = {
      if (upgradeResult.code == ApiRespType.NOT_FOUND)
        Await.result(db.run(initializeSchema.transactionally.asTry)
                       .map({
                         case Success(_) =>
                           ApiResponse(ApiRespType.OK, ExchMsg.translate("db.upgraded.successfully"))
                         case Failure(t) =>
                           if (t.getMessage.contains(upgradeNotNeededMsg))
                             ApiResponse(ApiRespType.OK, t.getMessage) // db already at latest schema
                           else {
                            ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.upgraded", t.toString))
                         }
                       }),
                     Duration(Configuration.getConfig.getInt("api.db.upgradeTimeoutSeconds"), SECONDS))
      else
        ApiResponse(ApiRespType.OK, ExchMsg.translate("db.upgraded.successfully"))
    }
    
    if (upgradeResult.code == ApiRespType.OK)
      logger.info(upgradeResult.msg)
    else if (upgradeResult.code == ApiRespType.ALREADY_EXISTS)
      logger.info("DB table schema up-to-date")
    else if (upgradeResult.code == ApiRespType.NOT_FOUND &&
             initializeResult.code == ApiRespType.OK)
      logger.info(initializeResult.msg)
    else if (initializeResult.code != ApiRespType.OK) {
      logger.error("ERROR: failure to init db: " + initializeResult.msg)
      system.terminate()
    }
    else {
      logger.error("ERROR: failure to upgrade db: " + upgradeResult.msg)
      system.terminate()
    }
    
    createRoot(db)
  }
    /* The timeout exception that this can throw is handled by the caller of upgradeDb()
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
        case Success(_) =>
          ApiResponse(ApiRespType.OK, ExchMsg.translate("db.upgraded.successfully")) // cant tell the diff between these 2, they both return Success(())
        case Failure(t) =>
          if (t.getMessage.contains(upgradeNotNeededMsg))
            ApiResponse(ApiRespType.OK, t.getMessage) // db already at latest schema
          else
            ApiResponse(ApiRespType.INTERNAL_ERROR, ExchMsg.translate("db.not.upgraded", t.toString)) // we hit some problem
      }
    }), Duration(Configuration.getConfig.getInt("api.db.upgradeTimeoutSeconds"), SECONDS)) // this is the rest of Await.result(), wait 1 minute for db init/upgrade to complete */
}
