package org.openhorizon.exchangeapi.table.schema

import org.apache.pekko.event.LoggingAdapter
import org.openhorizon.exchangeapi.table.agent.certificate.AgentCertificateVersionsTQ
import org.openhorizon.exchangeapi.table.agent.configuration.AgentConfigurationVersionsTQ
import org.openhorizon.exchangeapi.table.agent.software.AgentSoftwareVersionsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpattern.AgbotPatternsTQ
import org.openhorizon.exchangeapi.table.agreementbot.deploymentpolicy.AgbotBusinessPolsTQ
import org.openhorizon.exchangeapi.table.apikey.ApiKeysTQ
import org.openhorizon.exchangeapi.table.deploymentpattern.key.PatternKeysTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.BusinessPoliciesTQ
import org.openhorizon.exchangeapi.table.deploymentpolicy.search.SearchOffsetPolicyTQ
import org.openhorizon.exchangeapi.table.managementpolicy.ManagementPoliciesTQ
import org.openhorizon.exchangeapi.table.node.deploymentpolicy.NodePolicyTQ
import org.openhorizon.exchangeapi.table.node.error.NodeErrorTQ
import org.openhorizon.exchangeapi.table.node.group.NodeGroupTQ
import org.openhorizon.exchangeapi.table.node.group.assignment.NodeGroupAssignmentTQ
import org.openhorizon.exchangeapi.table.node.managementpolicy.status.NodeMgmtPolStatuses
import org.openhorizon.exchangeapi.table.node.status.NodeStatusTQ
import org.openhorizon.exchangeapi.table.resourcechange.ResourceChangesTQ
import org.openhorizon.exchangeapi.table.service.dockerauth.ServiceDockAuthsTQ
import org.openhorizon.exchangeapi.table.service.policy.ServicePolicyTQ
import org.openhorizon.exchangeapi.table.service.{SearchServiceTQ, ServicesTQ}
import org.openhorizon.exchangeapi.table.agent.AgentVersionsChangedTQ
import org.openhorizon.exchangeapi.utility.ApiTime
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._
import slick.lifted.TableQuery

import scala.collection.mutable.ListBuffer

object SchemaTQ  extends TableQuery(new SchemaTable(_)){

  // Returns the db actions necessary to get the schema from step-1 to step. The fromSchemaVersion arg is there because sometimes where you
  // originally came from affects how to get to the next step.
  def getUpgradeSchemaStep(fromSchemaVersion: Int, step: Int)(implicit logger: LoggingAdapter): DBIO[_] = {
    step match {
      case 0 => // v1.35.0 - no changes needed to get to time zero
        DBIO.seq()
      case 1 => // v1.37.0
        DBIO.seq(NodeStatusTQ.schema.create)
      case 2 => // v1.38.0
        DBIO.seq(sqlu"ALTER TABLE public.agbots DROP COLUMN IF EXISTS patterns",
                 AgbotPatternsTQ.schema.create)
      case 3 => // v1.45.0
        DBIO.seq(ServicesTQ.schema.create)
      case 4 =>
        DBIO.seq(/* WorkloadKeysTQ.schema.create, MicroserviceKeysTQ.schema.create, ServiceKeysTQ.schema.create, */
                 PatternKeysTQ.schema.create)
      case 5 => // v1.47.0
        DBIO.seq(sqlu"ALTER TABLE public.patterns ADD COLUMN IF NOT EXISTS services CHARACTER VARYING NOT NULL DEFAULT ''")
      case 6 => // v1.47.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS regservices CHARACTER VARYING NOT NULL DEFAULT ''")
      case 7 => // v1.47.0
        DBIO.seq(sqlu"ALTER TABLE public.nodeagreements ADD COLUMN IF NOT EXISTS services CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE nodeagreements ADD COLUMN IF NOT EXISTS agrsvcorgid CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE nodeagreements ADD COLUMN IF NOT EXISTS agrsvcpattern CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE nodeagreements ADD COLUMN IF NOT EXISTS agrsvcurl CHARACTER VARYING NOT NULL DEFAULT ''")
      case 8 => // v1.48.0
        val actions: List[DBIO[_]] =
          List(sqlu"ALTER TABLE agbotagreements ADD COLUMN IF NOT EXISTS serviceOrgid CHARACTER VARYING NOT NULL DEFAULT ''",
               sqlu"ALTER TABLE agbotagreements ADD COLUMN IF NOT EXISTS servicePattern CHARACTER VARYING NOT NULL DEFAULT ''",
               sqlu"ALTER TABLE agbotagreements ADD COLUMN IF NOT EXISTS serviceUrl CHARACTER VARYING NOT NULL DEFAULT ''",
               sqlu"ALTER TABLE nodestatus ADD COLUMN IF NOT EXISTS services CHARACTER VARYING NOT NULL DEFAULT ''")
        // If in this current level of code we started upgrading from 2 or less, that means we created the services table with the correct schema, so no need to modify it
        if (fromSchemaVersion >= 3)
          actions ++ List(sqlu"ALTER TABLE public.services RENAME COLUMN pkg TO imagestore")
        DBIO.seq(actions: _*) // convert the list of actions to a DBIO seq
      case 9 => // v1.52.0
        DBIO.seq(ServiceDockAuthsTQ.schema.create)
      case 10 => // v1.56.0
        DBIO.seq(sqlu"ALTER TABLE public.agbotpatterns ADD COLUMN IF NOT EXISTS nodeorgid CHARACTER VARYING NOT NULL DEFAULT ''")
      case 11 => // v1.56.0
        DBIO.seq(sqlu"ALTER TABLE public.servicedockauths ADD COLUMN IF NOT EXISTS username CHARACTER VARYING NOT NULL DEFAULT ''")
      case 12 => // v1.62.0
        DBIO.seq(sqlu"ALTER TABLE public.patterns DROP COLUMN IF EXISTS workloads",
                 sqlu"ALTER TABLE public.agbotagreements DROP COLUMN IF EXISTS workloadorgid",
                 sqlu"ALTER TABLE public.agbotagreements DROP COLUMN IF EXISTS workloadpattern",
                 sqlu"ALTER TABLE public.agbotagreements DROP COLUMN IF EXISTS workloadurl",
                 sqlu"ALTER TABLE public.nodestatus DROP COLUMN IF EXISTS microservice",
                 sqlu"ALTER TABLE public.nodestatus DROP COLUMN IF EXISTS workloads",
                 sqlu"ALTER TABLE public.nodeagreements DROP COLUMN IF EXISTS microservice",
                 sqlu"ALTER TABLE public.nodeagreements DROP COLUMN IF EXISTS workloadorgid",
                 sqlu"ALTER TABLE public.nodeagreements DROP COLUMN IF EXISTS workloadpattern",
                 sqlu"ALTER TABLE public.nodeagreements DROP COLUMN IF EXISTS workloadurl",
                 sqlu"DROP TABLE IF EXISTS public.properties",
                 sqlu"DROP TABLE IF EXISTS public.nodemicros",
                 sqlu"DROP TABLE IF EXISTS public.microservicekeys",
                 sqlu"DROP TABLE IF EXISTS public.microservices",
                 sqlu"DROP TABLE IF EXISTS public.workloadkeys",
                 sqlu"DROP TABLE IF EXISTS public.workloads",
                 sqlu"DROP TABLE IF EXISTS public.blockchains",
                 sqlu"DROP TABLE IF EXISTS public.bctypes")
      case 13 => // v1.63.0
        DBIO.seq(sqlu"ALTER TABLE public.services ADD COLUMN IF NOT EXISTS documentation CHARACTER VARYING NOT NULL DEFAULT ''",
                 // ResourcesTQ.schema.create,
                 // ResourceKeysTQ.schema.create,
                 // ResourceAuthsTQ.schema.create,
                 sqlu"ALTER TABLE public.services ADD COLUMN IF NOT EXISTS requiredResources CHARACTER VARYING NOT NULL DEFAULT ''")
      case 14 => // v1.64.0
        DBIO.seq(sqlu"ALTER TABLE public.orgs ADD COLUMN IF NOT EXISTS tags JSONB",
                 sqlu"CREATE INDEX IF NOT EXISTS ON public.orgs((tags->>'ibmcloud_id'))")
      case 15 => // v1.65.0 ?
        DBIO.seq(sqlu"DROP INDEX IF EXISTS orgs_expr_idx",
                 sqlu"CREATE UNIQUE INDEX IF NOT EXISTS orgs_ibmcloud_idx ON public.orgs((tags->>'ibmcloud_id'))")
      case 16 => // v1.67.0
        DBIO.seq(sqlu"DROP INDEX IF EXISTS orgs_ibmcloud_idx")
      case 17 => // v1.72.0
        DBIO.seq(sqlu"ALTER TABLE public.orgs ADD COLUMN IF NOT EXISTS orgtype CHARACTER VARYING NOT NULL DEFAULT ''")
      case 18 => // v1.77.0
        DBIO.seq(NodePolicyTQ.schema.create)
      case 19 => // v1.77.0
        DBIO.seq(ServicePolicyTQ.schema.create)
      case 20 => // v1.78.0
        DBIO.seq(BusinessPoliciesTQ.schema.create)
      case 21 => // v1.82.0
        DBIO.seq(sqlu"ALTER TABLE public.services DROP COLUMN IF EXISTS requiredresources",
                 sqlu"DROP TABLE IF EXISTS public.resourcekeys",
                 sqlu"DROP TABLE IF EXISTS public.resourceauths",
                 sqlu"DROP TABLE IF EXISTS public.resources")
      case 22 => // v1.82.0
        DBIO.seq(AgbotBusinessPolsTQ.schema.create)
      case 23 => // v1.83.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS arch CHARACTER VARYING NOT NULL DEFAULT ''")
      case 24 => // v1.84.0
        DBIO.seq(sqlu"ALTER TABLE public.patterns ADD COLUMN IF NOT EXISTS userinput CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.businesspolicies ADD COLUMN IF NOT EXISTS userinput CHARACTER VARYING NOT NULL DEFAULT ''")
      case 25 => // v1.87.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS userinput CHARACTER VARYING NOT NULL DEFAULT ''")
      case 26 => // v1.92.0
        DBIO.seq(sqlu"ALTER TABLE public.users ADD COLUMN IF NOT EXISTS updatedBy CHARACTER VARYING NOT NULL DEFAULT ''")
      case 27 => // v1.102.0
        DBIO.seq(NodeErrorTQ.schema.create)
      case 28 => // v1.92.0
        DBIO.seq(sqlu"ALTER TABLE public.nodestatus ADD COLUMN IF NOT EXISTS runningServices CHARACTER VARYING NOT NULL DEFAULT ''")
      case 29 => // v1.122.0
        DBIO.seq(ResourceChangesTQ.schema.create)
      case 30 => // v2.1.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS heartbeatintervals CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.orgs ADD COLUMN IF NOT EXISTS heartbeatintervals CHARACTER VARYING NOT NULL DEFAULT ''")
      case 31 => // v2.12.0
        DBIO.seq(sqlu"CREATE INDEX IF NOT EXISTS org_index on public.resourcechanges(orgid)",
                 sqlu"CREATE INDEX IF NOT EXISTS id_index ON public.resourcechanges(id)",
                 sqlu"CREATE INDEX IF NOT EXISTS cat_index ON public.resourcechanges(category)",
                 sqlu"CREATE INDEX IF NOT EXISTS pub_index ON public.resourcechanges(public)")
      case 32 => // v2.13.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS lastupdated CHARACTER VARYING NOT NULL DEFAULT ''")
      case 33 => // v2.14.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS nodetype CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.services ADD COLUMN IF NOT EXISTS clusterdeployment CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.services ADD COLUMN IF NOT EXISTS clusterdeploymentsignature CHARACTER VARYING NOT NULL DEFAULT ''")
      case 34 => // v2.17.0
        DBIO.seq(sqlu"ALTER TABLE public.resourcechanges ALTER COLUMN changeid TYPE BIGINT",
                 sqlu"ALTER TABLE public.resourcechanges ADD COLUMN IF NOT EXISTS temp TIMESTAMPTZ",
                 sqlu"UPDATE public.resourcechanges SET temp = to_timestamp(lastupdated, 'YYYY-MM-DD THH24:MI:SS.MS')::timestamp with time zone at time zone 'Etc/UTC'",
                 sqlu"ALTER TABLE public.resourcechanges DROP COLUMN IF EXISTS lastupdated",
                 sqlu"ALTER TABLE public.resourcechanges RENAME COLUMN IF EXISTS temp to lastupdated",
                 sqlu"CREATE INDEX IF NOT EXISTS lu_index on public.resourcechanges(lastupdated)")
      case 35 => // v2.18.0
        DBIO.seq(sqlu"ALTER TABLE public.agbotmsgs DROP CONSTRAINT IF EXISTS node_fk")
      case 36 => // v2.35.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ALTER COLUMN IF EXISTS lastheartbeat DROP NOT NULL")
      case 37 =>
        DBIO.seq(SearchOffsetPolicyTQ.schema.create)
      case 38 => // v2.42.0
        DBIO.seq(sqlu"ALTER TABLE public.users ADD COLUMN IF NOT EXISTS hubadmin boolean DEFAULT FALSE",
                 sqlu"ALTER TABLE public.orgs ADD COLUMN IF NOT EXISTS limits CHARACTER VARYING NOT NULL DEFAULT ''")
      case 39 => // v2.44.0
        DBIO.seq(sqlu"ALTER TABLE public.resourcechanges DROP CONSTRAINT IF EXISTS orgid_fk")
      case 40 => // version 2.57.0
        DBIO.seq(sqlu"ALTER TABLE public.nodepolicies ADD COLUMN IF NOT EXISTS label CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.nodepolicies ADD COLUMN IF NOT EXISTS description CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.servicepolicies ADD COLUMN IF NOT EXISTS label CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.servicepolicies ADD COLUMN IF NOT EXISTS description CHARACTER VARYING NOT NULL DEFAULT ''")
      case 41 =>
        DBIO.seq(sqlu"ALTER TABLE public.patterns ADD COLUMN IF NOT EXITS secretbinding CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.businesspolicies ADD COLUMN IF NOT EXISTS secretbinding CHARACTER VARYING NOT NULL DEFAULT ''")
      case 42 => // v2.89.0
        DBIO.seq(ManagementPoliciesTQ.schema.create)
      case 43 => // v2.57.0
        DBIO.seq(sqlu"ALTER TABLE public.nodepolicies ADD COLUMN IF NOT EXISTS deployment CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.nodepolicies ADD COLUMN IF NOT EXISTS management CHARACTER VARYING NOT NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.nodepolicies ADD COLUMN IF NOT EXISTS nodepolicyversion CHARACTER VARYING NOT NULL DEFAULT ''")
      case 44 =>
        DBIO.seq(sqlu"ALTER TABLE public.managementpolicies DROP COLUMN IF EXISTS agentupgradepolicy",
                 sqlu"ALTER TABLE public.managementpolicies ADD COLUMN IF NOT EXISTS allowDowngrade BOOL NOT NULL DEFAULT FALSE",
                 sqlu"ALTER TABLE public.managementpolicies ADD COLUMN IF NOT EXISTS manifest CHARACTER VARYING NULL DEFAULT ''",
                 sqlu"ALTER TABLE public.managementpolicies ADD COLUMN IF NOT EXISTS start CHARACTER VARYING NOT NULL DEFAULT 'now'",
                 sqlu"ALTER TABLE public.managementpolicies ADD COLUMN IF NOT EXISTS startwindow BIGINT NOT NULL DEFAULT 0")
      // NODE: IF ADDING A TABLE, DO NOT FORGET TO ALSO ADD IT TO ExchangeApiTables.initDB and dropDB
      case 45 => // v2.95.0
        DBIO.seq(NodeMgmtPolStatuses.schema.create)
      case 46 => // v2.96.0
        DBIO.seq(AgentCertificateVersionsTQ.schema.create,
                 AgentConfigurationVersionsTQ.schema.create,
                 AgentSoftwareVersionsTQ.schema.create,
                 AgentVersionsChangedTQ.schema.create)
      case 47 => // v2.100.0
        DBIO.seq(sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN time_start_actual DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN version_certificate DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN version_configuration DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN time_end DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN error_message DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN version_software DROP NOT NULL",
                 sqlu"ALTER TABLE public.management_policy_status_node ALTER COLUMN status DROP NOT NULL")
      case 48 => // v2.101.0
        DBIO.seq(sqlu"ALTER TABLE public.agent_version_certificate ADD COLUMN IF NOT EXISTS PRIORITY BIGINT NULL;",
                 sqlu"ALTER TABLE public.agent_version_configuration ADD COLUMN IF NOT EXISTS PRIORITY BIGINT NULL;",
                 sqlu"ALTER TABLE public.agent_version_software ADD COLUMN IF NOT EXISTS PRIORITY BIGINT NULL;",
                 sqlu"CREATE UNIQUE INDEX IF NOT EXISTS idx_avcert_priority ON public.agent_version_certificate(organization, priority);",
                 sqlu"CREATE UNIQUE INDEX IF NOT EXISTS idx_avconfig_priority ON public.agent_version_configuration(organization, priority);",
                 sqlu"""CREATE UNIQUE INDEX IF NOT EXISTS idx_avsoft_priority ON public.agent_version_software("version", priority);""")
      case 49 =>
        DBIO.seq(NodeGroupTQ.schema.create,
                 NodeGroupAssignmentTQ.schema.create)
      case 50 => // v2.108.0
        DBIO.seq(sqlu"ALTER TABLE public.node_group ALTER COLUMN description DROP NOT NULL;")
      case 51 => // v2.109.0
        DBIO.seq(sqlu"ALTER TABLE public.node_group ADD COLUMN IF NOT EXISTS admin BOOL NOT NULL DEFAULT FALSE;")
      case 52 => // v2.111.0
        DBIO.seq(sqlu"ALTER TABLE public.businesspolicies ADD COLUMN IF NOT EXISTS cluster_namespace varchar NULL;",
                 sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS cluster_namespace varchar NULL;",
                 sqlu"ALTER TABLE public.patterns ADD COLUMN IF NOT EXISTS cluster_namespace varchar NULL;")
      case 53 => // v2.113.0
        DBIO.seq(sqlu"""UPDATE public.businesspolicies SET service = regexp_replace(service, '}}$$', '},"clusterNamespace": "' || cluster_namespace || '"}') WHERE cluster_namespace NOTNULL AND service NOT LIKE '%},"clusterNamespace": "%"}';""",
                 sqlu"ALTER TABLE public.businesspolicies DROP COLUMN IF EXISTS cluster_namespace;")
      case 54 => // v2.115.0
        DBIO.seq(SearchServiceTQ.schema.create)
      case 55 => // v2.116.0
        DBIO.seq(sqlu"ALTER TABLE public.nodes ADD is_namespace_scoped bool NOT NULL DEFAULT false;")
      case 56 => // v2.127.0
        DBIO.seq(
          // Add new User primary key columns to downstream tables for reference.
          sqlu"ALTER TABLE public.agbots ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          sqlu"ALTER TABLE public.businesspolicies ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          sqlu"ALTER TABLE public.managementpolicies ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          sqlu"ALTER TABLE public.nodes ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          sqlu"ALTER TABLE public.patterns ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          sqlu"ALTER TABLE public.services ADD COLUMN IF NOT EXISTS owner_uuid UUID NOT NULL;",
          
          // Add new columns to our existing Users table. These need to be nullable here.
          sqlu"ALTER TABLE public.users ADD COLUMN IF NOT EXISTS modified_at TIMESTAMP NULL;",
          sqlu"ALTER TABLE public.users ADD COLUMN IF NOT EXISTS modified_by UUID NULL;",
          sqlu"""ALTER TABLE public.users ADD COLUMN IF NOT EXISTS "user" UUID NULL;""",
          sqlu"ALTER TABLE public.users ADD COLUMN IF NOT EXISTS username_new VARCHAR NULL;",
          
          // Fill-in our new columns.
          // No great means of converting backwards from a string to a timestamp. At best these can only be parsed approximations.
          sqlu"""
                UPDATE public.users
                SET modified_at = coalesce(to_timestamp(lastupdated, 'yyyy-MM-ddTHH24:MI:SS')::timestamptz, CURRENT_TIMESTAMP);
              """,
          // Split Organization from existing Organization/Username combination.
          sqlu"""
                UPDATE public.users
                SET orgid = rtrim(substring(username, '^[[:alnum:]]*/{1,1}'), '/')
                WHERE orgid IS NULL;
              """,
          // Postgresql function for generating v4 UUIDs. https://www.postgresql.org/docs/13/functions-uuid.html
          // Support for v7 UUIDs will be added to a future Postgresql v17 minor release. Not available as of 2025-05-01 PostgreSQL v17.4.
          sqlu"""
                UPDATE public.users
                SET "user" = gen_random_uuid ()
                WHERE "user" IS NULL;
              """,
          // Split Username from existing Organization/Username combination.
          sqlu"""
                UPDATE public.users
                SET username_new = regexp_replace(username, '^[[:alnum:]]*/{1,1}', '')
                WHERE username_new IS NULL;
              """,
          // Convert User references from Org/Usr combo strings to UUIDs
          sqlu"""
                UPDATE public.users a
                SET modified_by = b.by
                FROM (SELECT c.user AS user,
                             d.user AS by
                      FROM public.users c
                      JOIN public.users d
                      ON c.updatedby = d.username) b
                WHERE a.user = b.user;
              """,
          
          // User reference conversion.
          sqlu"""
                UPDATE public.agbots a
                SET owner_uuid = u.user
                FROM public.users u
                WHERE a.owner = u.username;
              """,
          sqlu"""
                UPDATE public.businesspolicies p
                SET owner_uuid = u.user
                FROM public.users u
                WHERE p.owner = u.username;
              """,
          sqlu"""
                UPDATE public.managementpolicies p
                SET owner_uuid = u.user
                FROM public.users u
                WHERE p.owner = u.username;
              """,
          sqlu"""
                UPDATE public.nodes n
                SET owner_uuid = u.user
                FROM public.users u
                WHERE n.owner = u.username;
              """,
          sqlu"""
                UPDATE public.patterns p
                SET owner_uuid = u.user
                FROM public.users u
                WHERE p.owner = u.username;
              """,
          sqlu"""
                UPDATE public.services s
                SET owner_uuid = u.user
                FROM public.users u
                WHERE s.owner = u.username;
              """,
          
          // Drop existing foreign keys.
          sqlu"ALTER TABLE public.agbots DROP CONSTRAINT IF EXISTS user_fk;",
          sqlu"ALTER TABLE public.businesspolicies DROP CONSTRAINT IF EXISTS user_fk;",
          sqlu"ALTER TABLE public.managementpolicies DROP CONSTRAINT IF EXISTS user_fk;",
          sqlu"ALTER TABLE public.nodes DROP CONSTRAINT IF EXISTS user_fk;",
          sqlu"ALTER TABLE public.patterns DROP CONSTRAINT IF EXISTS user_fk;",
          sqlu"ALTER TABLE public.services DROP CONSTRAINT IF EXISTS user_fk;",
          
          // Drop existing User reference columns
          sqlu"ALTER TABLE public.agbots DROP COLUMN IF EXISTS owner;",
          sqlu"ALTER TABLE public.businesspolicies DROP COLUMN IF EXISTS owner;",
          sqlu"ALTER TABLE public.managementpolicies DROP COLUMN IF EXISTS owner;",
          sqlu"ALTER TABLE public.nodes DROP COLUMN IF EXISTS owner;",
          sqlu"ALTER TABLE public.patterns DROP COLUMN IF EXISTS owner;",
          sqlu"ALTER TABLE public.services DROP COLUMN IF EXISTS owner;",
          
          // Rename new User reference columns
          sqlu"ALTER TABLE public.agbots RENAME COLUMN owner_uuid TO owner;",
          sqlu"ALTER TABLE public.businesspolicies RENAME COLUMN owner_uuid TO owner;",
          sqlu"ALTER TABLE public.managementpolicies RENAME COLUMN owner_uuid TO owner;",
          sqlu"ALTER TABLE public.nodes RENAME COLUMN owner_uuid TO owner;",
          sqlu"ALTER TABLE public.patterns RENAME COLUMN owner_uuid TO owner;",
          sqlu"ALTER TABLE public.services RENAME COLUMN owner_uuid TO owner;",
          
          // Create our new Users table
          sqlu"""
                CREATE TABLE IF NOT EXISTS public.users_schema_56 (
                   created_at timestamp NOT NULL,
                   email VARCHAR NULL,
                   identity_provider VARCHAR NOT NULL DEFAULT 'Open Horizon',
                   is_hub_admin BOOL NOT NULL DEFAULT FALSE,
                   is_org_admin BOOL NOT NULL DEFAULT FALSE,
                   modified_at TIMESTAMP NOT NULL,
                   modified_by UUID NULL,
                   organization VARCHAR NOT NULL,
                   password VARCHAR NULL,
                   "user" UUID NOT NULL,
                   username VARCHAR NOT NULL,
                   CONSTRAINT users_pk PRIMARY KEY ("user"),
                   CONSTRAINT users_uk UNIQUE (organization, username),
                   CONSTRAINT users_org_fk FOREIGN KEY (organization) REFERENCES public.orgs(orgid) ON DELETE CASCADE ON UPDATE CASCADE
                   CONSTRAINT users_usr_fk FOREIGN KEY (modified_by) REFERENCES public.users("user") ON DELETE SET NULL ON UPDATE CASCADE
                   CONSTRAINT users_root_check CHECK (((organization = 'root') AND (username = 'root')) OR (NOT (is_hub_admin AND is_org_admin))));
              """,
          
          // Database table migration
          sqlu"""
                INSERT INTO public.users_schema_56 (created_at,
                                                    email,
                                                    is_hub_admin,
                                                    is_org_admin,
                                                    modified_at,
                                                    modified_by,
                                                    organization,
                                                    password,
                                                    "user",
                                                    username)
                SELECT modified_at,
                       email,
                       admin,
                       hubadmin,
                       modified_at,
                       modified_by,
                       orgid,
                       password,
                       "user",
                       username_new
                FROM public.users;
              """,
          
          // Drop existing User table
          sqlu"DROP TABLE IF EXISTS public.users;",
          
          // Rename new User table
          sqlu"ALTER TABLE IF EXISTS public.users_schema_56 RENAME TO users;",
          
          // Recreate foreign key references
          sqlu"""ALTER TABLE public.agbots ADD CONSTRAINT agbots_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          sqlu"""ALTER TABLE public.businesspolicies ADD CONSTRAINT deploypol_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          sqlu"""ALTER TABLE public.managementpolicies ADD CONSTRAINT mgmtpol_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          sqlu"""ALTER TABLE public.nodes ADD CONSTRAINT nodes_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          sqlu"""ALTER TABLE public.patterns ADD CONSTRAINT pattrns_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          sqlu"""ALTER TABLE public.services ADD CONSTRAINT svcs_user_fk FOREIGN KEY (owner) REFERENCES public.users ("user");""",
          
          // Create missing foreign key references
          sqlu"""ALTER TABLE public.nodepolicies ADD CONSTRAINT node_deploy_pol_fk_nodes FOREIGN KEY (nodeid) REFERENCES public.nodes ("id");""",
          sqlu"""ALTER TABLE public.nodeerror ADD CONSTRAINT node_error_fk_nodes FOREIGN KEY (nodeid) REFERENCES public.nodes ("id");""",
          sqlu"""ALTER TABLE public.management_policy_status_node ADD CONSTRAINT node_mgmt_pol_status_fk_nodes FOREIGN KEY (node) REFERENCES public.nodes ("id");""",
          
          // Create missing indexes on foreign keys
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_agree_fk_agbots ON public.agbotagreements(agbotid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_deploy_pol_fk_agbots ON public.agbotbusinesspols(agbotid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_msg_fk_agbots ON public.agbotmsgs(agbotid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_pattern_fk_agbots ON public.agbotpatterns(agbotid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_fk_orgs ON public.agbots(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_agbot_fk_users ON public.agbots(owner);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_deploy_pattern_fk_orgs ON public.patterns(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_deploy_pattern_fk_users ON public.patterns(owner);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_deploy_pattern_keys_fk_patterns ON public.patternkeys(patternid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_deploy_pol_fk_orgs ON public.businesspolicies(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_deploy_pol_fk_users ON public.businesspolicies(owner);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_mgmt_pol_fk_orgs ON public.managementpolicies(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_mgmt_pol_fk_users ON public.managementpolicies(owner);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_agree_fk_nodes ON public.nodeagreements(nodeid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_deploy_pol_fk_nodes ON public.nodepolicies(nodeid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_error_fk_nodes ON public.nodeerror(nodeId);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_grp_assgn_fk_nodes ON public.node_group_assignment(node);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_grp_assgn_fk_node_grps ON public.node_group_assignment(group);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_mgmt_pol_stat_fk_mgmt_pols ON public.management_policy_status_node(policy);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_mgmt_pol_stat_fk_nodes ON public.management_policy_status_node(node);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_msg_fk_agbots ON public.nodemsgs(agbotid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_msg_fk_nodes ON public.nodemsgs(nodeid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_status_fk_nodes ON public.nodestatus(nodeId);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_fk_orgs ON public.nodemsgs(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_node_fk_users ON public.nodemsgs(users);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_search_offset_pol_fk_deploy_pols ON public.search_offset_policy(policy);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_serv_dock_auth_fk_services ON public.servicedockauths(serviceid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_serv_key_fk_services ON public.servicekeys(serviceid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_serv_pol_fk_services ON public.servicekeys(serviceid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_service_fk_orgs ON public.services(orgid);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_service_fk_users ON public.services(owner);""",
          sqlu"""CREATE INDEX IF NOT EXISTS idx_user_fk_users ON public.users(modified_by);""",
          ApiKeysTQ.schema.create)
              
      case other => // should never get here
        logger.error("getUpgradeSchemaStep was given invalid step "+other); DBIO.seq()
    }
  }

  val latestSchemaVersion: Int = 56    // NOTE: THIS MUST BE CHANGED WHEN YOU ADD TO getUpgradeSchemaStep() above
  val latestSchemaDescription: String = ""
  // Note: if you need to manually set the schema number in the db lower: update schema set schemaversion = 12 where id = 0;

  def isLatestSchemaVersion(fromSchemaVersion: Int): Boolean = fromSchemaVersion >= latestSchemaVersion

  def getSchemaRow = this.filter(_.id === 0)
  def getSchemaVersion = this.filter(_.id === 0).map(_.schemaversion)

  // Returns a sequence of DBIOActions that will upgrade the DB schema from fromSchemaVersion to the latest schema version.
  // It is assumed that this sequence of DBIOActions will be run transactionally.
  def getUpgradeActionsFrom(fromSchemaVersion: Int)(implicit logger: LoggingAdapter): DBIO[_] = {
    if (isLatestSchemaVersion(fromSchemaVersion)) {   // nothing to do
      logger.debug("Already at latest DB schema version - nothing to do")
      return DBIO.seq()
    }
    val actions: ListBuffer[DBIO[_]] = ListBuffer[DBIO[_]]()
    for (i <- (fromSchemaVersion+1) to latestSchemaVersion) {
      logger.debug("Adding DB schema upgrade actions to get from schema version "+(i-1)+" to "+i)
      //actions += upgradeSchemaVector(i)
      actions += getUpgradeSchemaStep(fromSchemaVersion, i)
    } += getSetVersionAction // record that we are at the latest schema version now
    DBIO.seq(actions.toList: _*)      // convert the list of actions to a DBIO seq
  }

  def getSetVersionAction: DBIO[_] = SchemaRow(0, latestSchemaVersion, latestSchemaDescription, ApiTime.nowUTC).upsert
  // Note: to manually change the schema version in the DB for testing: update schema set schemaversion=0;

  // Use this together with ExchangeApiTables.deleteNewTables to back out the latest schema update, so you can redo it
  def getDecrementVersionAction(currentSchemaVersion: Int): DBIO[_] = SchemaRow(0, currentSchemaVersion-1, "decremented schema version", ApiTime.nowUTC).upsert

  def getDeleteAction: DBIO[_] = getSchemaRow.delete
}
