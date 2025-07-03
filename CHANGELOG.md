# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.131.0](https://github.com/open-horizon/exchange-api/pull/788) - 2025-06-30
- Issue 787: Added /users/apikey and /users/iamapikey endpoints to let clients retrieve their own user info when using API key authentication without explicitly setting the username (e.g. to support how hzn and other components construct requests)

## [2.130.0](https://github.com/open-horizon/exchange-api/pull/789) - 2025-07-02
- Issue 769: Added basic OAuth capabilities using bearer access tokens and requests to external provided
             userinfo endpoints. A custom header with configuration has been added to supply the intended organization
             to be used for the authenticated identity and any user creation via OAuth.

## [2.129.0](https://github.com/open-horizon/exchange-api/pull/779) - 2025-06-20
- Issue 778: Added a second attempt authentication mechanism that forces a cache synchronization with the database.
             This is only useful when using multiple Exchange instances in a container orchestration cluster.
             Cache invalidation already works as intended for a single instance.   

## [2.128.1](https://github.com/open-horizon/exchange-api/pull/777) - 2025-06-18
- Issue 776: Fixed log level of basic response information.

## [2.128.0](https://github.com/open-horizon/exchange-api/pull/774) - 2025-06-12
- Add support for internal API key management and authentication.
- Create APIKEY table and handle schema migration.
- Add object model and service methods for API key operations.

## [2.127.0](https://github.com/open-horizon/exchange-api/pull/773) - 2025-06-03
- Guava has been replaced by Caffeine as the backend cache implementation. ScalaCache is still the wrapper.
  - There are other Caffeine implementations and wrappers out there. This is a start.
  - The logger can track the individual cache hits and misses per key. Presently disabled in the logger config.
    - Added appropriate config endpoints to the configuration file, as well as, environmental variable.
- The following 15 individual caches have been removed and/or consolidated:
  - usersAdmin
  - usersHubAdmin
  - nodesOwner
  - agbotsOwner
  - servicesOwner
  - patternsOwner
  - businessOwner
  - servicesOwner
  - managementPolicyOwner
  - patternsPublic
  - servicesPublic
  - businessPublic
  - managementPolicyPublic
  - ids
  - The IbmCloudAuth cache still exists, but it and its module are currently disabled.
- Two new caches have been created:
  - cacheResourceIdentity - Used for Authentication and requesting resource identity.
  - cacheResoourceOwnership - Used for Authorization and the requested resource identity.
  - These new caches still use the old configuration values. These will very likely need to be re-balanced in scale testing.
- Authentication, Authorization and Identity are now much more their own independent concepts.
  - Each is more individually extensible and testable. More work in IEAM v5.1+
  - This release is focused more on Authentication, and a moderate amount on Identity. Authorization changes are to enable the former at this stage.
- User Resource Identity is now based on UUIDs, not formatted strings.
  - This will also come to Agreement Bots and Nodes in the  Future. (Unified Identity)
  - Possibly other system resources as well (policies, patterns, groups, services, etc). work for another day...
- Database modelling changes:
  - Table for Users has been rebuilt.
  - All downstream `owner` fields are now type UUID. Foreign keys remade.
  - All client data is migrated with these schema changes.
- Added missing database indexes on all foreign keys.
- Added a few missing foreign keys to the database.
- Red Hat UBI 10 support added.
- JDK21 support added.
- Most Get or Get all resource routes have been rebuilt.
- All User routes have been rebuilt.
- Rebuilt and consolidated the Catalog routes.
- Hashpw routes have been removed from the Exchange.
- Deprecated BCrypt support for credential hashing. Replaced with Argon2id.
  - Configurable through the config file, or the appropriate environment variable.
  - The Exchange will auto-magically*TM convert all hashes for all resources on authentication.
- Updated GitHub workflows to use JDK 21.
- Dependency Updates:
  - ch.epfl.scala.sbt-scalafix                             0.14.3 -> 0.11.1
  - ch.qos.logback.logback-classic                          1.5.6 -> 1.5.18
  - com.github.cb372.scalacache-guava                      0.28.0 ->        [Removed]
  - com.github.cb372.scalacache-caffeine                          -> 0.28.0 [Added]
  - com.github.pjfanning.pekko-http-jackson                 3.0.0 -> 3.2.0
  - com.github.sbt.sbt-native-packager                     1.9.13 -> 1.11.1
  - com.osinka.i18n.scala-i18n                              1.0.3 -> 1.1.0
  - io.spray.sbt-revolver                                   0.9.1 -> 0.10.0
  - org.apache.pekko.pekko-http                             1.1.0 -> 1.2.0
  - org.apache.pekko.pekko-http-xml                         1.1.0 -> 1.2.0
  - org.apache.pekko.pekko-http-cors                        1.1.0 -> 1.2.0
  - org.apache.pekko.pekko-slf4j                            1.0.2 -> 1.1.3
  - org.apache.pekko.pekko-protobuf-v3                      1.0.2 -> 1.1.3
  - org.apache.pekko.pekko-stream                           1.0.2 -> 1.1.3
  - org.bouncycastle.bcprov-jdk18on                               -> 1.80  [Added]
  - org.postgresql.postgresql                              42.7.1 -> 42.7.6
  - org.scalacheck.scalacheck                              1.17.0 -> 1.18.1
  - org.scalatest.scalatest                           3.3.0-SNAP2 -> 3.3.0-SNAP4
  - org.scoverage.sbt-scoverage                             2.0.6 -> 2.3.1
  - org.springframework.security.spring-security-core             -> 6.5.0 [Added]
  - JDK                                                        17 -> 21
  - SBT                                                    1.10.5 -> 1.11.0
  - swagger ui                                             5.11.0 -> 5.22.0
  - Red Hat UBI-Minimal                                         9 -> 10

## [2.126.1](https://github.com/open-horizon/exchange-api/pull/747) - 2025-02-13
- Added backwards compatible tls config variables to the default config.

## [2.126.0](https://github.com/open-horizon/exchange-api/pull/728) - 2024-11-04
- Issue 201: The number of registered and unregistered Nodes has been added as reported metrics.
- Issue 624: Http 404 is returned when referencing an non-existent Organization.
- Rebuilt ../admin/status
- Rebuilt ../admin/orgstatus
- Rebuilt ../org/{org}/status
- Fixed some issues with generated swagger document
- Minor dependency cleanup.
- Minor whitespace adjustment
- SBT 1.10.1 -> 1.10.5


## [2.125.3](https://github.com/open-horizon/exchange-api/pull/730) - 2024-11-04
- issue 594: Rework unit-test for ApiUtilsSuite.
- Split ApiUtilsSuite in two tests: TestApiUtilsTime and TestNodeAgbotTokenValidation.
- Extend number of tests for TestApiUtilsTime and TestNodeAgbotTokenValidation.
- Add validation for invalid time format.

## [2.125.2](https://github.com/open-horizon/exchange-api/pull/726) - 2024-10-31
- issue 607: Rework unit-test for version.
- Increased the number of test cases to cover corner cases for Version.
- Fixed issues below that were found during refactoring test version suite.

## [2.125.1](https://github.com/open-horizon/exchange-api/pull/725) - 2024-10-22
- Issue 724: Version conflicts in library(pekko-http) dependencies
- pekko-http 1.0.1 -> 1.1.0
- pekko-http-jackson 2.3.3 -> 3.0.0
- pekko-http-cors 1.0.1 -> 1.1.0
- pekko-slf4j 1.0.1 -> 1.1.1
- swagger-pekko-http 2.12.0 -> 2.14.0
- Fixed warnings

## [2.125.0](https://github.com/open-horizon/exchange-api/pull/720) - 2024-09-30
- Removed support for TLS v1.2.
- Removed an API key authentication pathway.

## [2.124.0](https://github.com/open-horizon/exchange-api/pull/718) - 2024-09-14
- Application configuration overhaul.
  - Some database configuration changes are not backwards compatible. 
- GET methods for Node resources no longer return passwords for admin user types, unless directly owned.
- Added new rest paths for deployment patterns and policies aligning and clarifying these resources.
  - `.../v1/orgs/<organization>/deployment/patterns/...`
  - `.../v1/orgs/<organization>/deployment/policies/...`

## [2.123.0](https://github.com/open-horizon/exchange-api/pull/715) - 2024-04-19
- pekko-http-xml 1.0.0 -> 1.0.1
- Reorganized class references in the Swagger documentation generator.

## [2.122.0](https://github.com/open-horizon/exchange-api/pull/714) - 2024-01-29
- Issue 646: Transitioned The Exchange to Apache Pekko from Lightbind's Akka.
- akka-http                  10.2.7 -> pekko-http 1.0.0
- akka-http-testkit          10.2.7 -> pekko-http-testkit 1.0.0
- akka-http-xml              10.2.7 -> pekko-http-xml 1.0.0
- akka-stream-testkit        2.6.16 -> pekko-stream-testkit 1.0.2
- akka-testkit               2.6.16 -> pekko-testkit 1.0.2
- Scala                     2.13.10 -> Scala 2.13.11
- akka-http-cors              1.1.2 -> pekko-http-cors 1.0.0
- akka-http-jackson          1.39.2 -> pekko-http-jackson 2.0.0
- postgresql                 42.6.0 -> 42.7.1
- SBT                         1.9.6 -> 1.9.8
- swagger-akka-http           2.6.0 -> swagger-pekko-http 2.12.0
- swagger-scala-module       2.11.0 -> 2.12.0
- Swagger UI                  5.1.0 -> 5.11.0

## [2.121.0](https://github.com/open-horizon/exchange-api/pull/713) - 2023-11-16
- Restructured the source for Catalog, Deployment Patterns and Policies, Management Policies, Nodes, Node Groups, and Services.

## [2.120.0](https://github.com/open-horizon/exchange-api/pull/710) - 2023-10-18
- Restructured the source for Users, Services, Organizations, and some of the search routes.
- Corrected a few Swagger documentation errors.
- Sbt 1.9.2 -> 1.9.6

## [2.119.0](https://github.com/open-horizon/exchange-api/pull/693) - 2023-07-26
- Issue 692: Added new attribute `enableNodeLevelSecrets` to `secretBinding` for Deployment Patterns and Policies.
- Reorganized utility and auth source objects.
- Renamed a few test source packages.

## [2.118.0](https://github.com/open-horizon/exchange-api/pull/691) - 2023-07-25
- Rebuilt ../orgs/<org>/changes route.
- Reorganized the majority of table source.
- Fixed error message typo.

## [2.117.0](https://github.com/open-horizon/exchange-api/pull/687) - 2023-07-13
- Issue 686: Added rest parameter `manifest` to Get /v1/orgs/<org>/managementpolicies route.

## [2.116.0](https://github.com/open-horizon/exchange-api/pull/685) - 2023-07-11
- Issue 676: Added `isNamespaceScoped` attribute to Nodes.
- Reorganized table package object source for Nodes and related.
- SBT 1.9.0 -> 1.9.2

## [2.115.3](https://github.com/open-horizon/exchange-api/pull/684) - 2023-07-07
- Issue 675: Node tokens can be changed by all User types, and public keys can only be [un]set without changing keys by Nodes.

## [2.115.2](https://github.com/open-horizon/exchange-api/pull/681) - 2023-07-07
- Issue 675: Removed extra regular expressions modifying searched Service's URL.

## [2.115.1](https://github.com/open-horizon/exchange-api/pull/680) - 2023-07-06
- Issue 675: Fixed User and Node access to searching all Patterns and Services within the Node's Organization.
- Corrected error messages.

## [2.115.0](https://github.com/open-horizon/exchange-api/pull/679) - 2023-06-29
- Issue 675: Patch /orgs/<organization>/nodes/<node> No longer allows setting the Node's token if a public key is set.
    - Added a new database table to handle Service querying and matching.
- pattern and userInput are now User archetype authorization scoped.
- Reorganized the Node resource source.
- postgresql           42.5.3 -> 42.6.0
- SBT                  1.8.2  -> 1.9.0
- swagger-scala-module 2.8.2  -> 2.11.0
- Swagger UI           4.15.5 -> 5.1.0

## [2.114.0](https://github.com/open-horizon/exchange-api/pull/674) - 2023-05-01
- Issue 639: Moved top-level attribute clusterNamespace to a sub-attribute under service for Deployment Policies.

## [2.113.0](https://github.com/open-horizon/exchange-api/pull/673) - 2023-04-26
- Issue 637 - Added CORS support. Can be configurated via the Exchange's config.json file.
- Changed Java package path to the prefix org.openhorizon.
- Moved source handling routing to new sub-package org.openhorizon.route..
    - AdminRoutes and AgbotRoutes have been further sub-divided based on rest resource.
- Deprecated custom methods for handling Akka configuration parameters in favor of framework defaults.
    - Backwards compatibility is still maintained.

## [2.112.0](https://github.com/open-horizon/exchange-api/pull/671) - 2023-04-07
- Issue 640: Added `clusterNamespace` attribute to Deployment Patterns.
- Issue 641: Added `clusterNamespace` attribute to Nodes.

## [2.111.0](https://github.com/open-horizon/exchange-api/pull/670) - 2023-04-03
- Issue 639: Added `clusterNamespace` attribute to Deployment Policies.

## [2.110.1](https://github.com/open-horizon/exchange-api/pull/664) - 2023-03-01
- Issue 662: Slick's schema.createIfNotExists function is not creating foreign keys. Reverting back to the simple create function.

## [2.110.0](https://github.com/open-horizon/exchange-api/pull/663) - 2023-02-28
- Issue 662:
    - Removed database schema change that was added in error.
    - Reworked the schema upgrade function to fully roll back to the schema version at boot when encountering an error.

## [2.109.2](https://github.com/open-horizon/exchange-api/pull/661) - 2023-02-23
- Issue 657: Added additional ddl clauses to the Exchange's schema upgrade path.

## [2.109.1](https://github.com/open-horizon/exchange-api/pull/660) - 2023-02-23
- Issue 657: Removed erroneous quotes around column name to be added to database.

## [2.109.0](https://github.com/open-horizon/exchange-api/pull/659) - 20230-02-21
- Issue 657: Restricted Users from adding Nodes generally to any available Node Group. To add a Node to a Node Group that Node Group must be empty, or only have the User's Nodes in the Group. Added a flag for Node Groups that were created by Organization Admins. Users may only remove their owned nodes from these groups, no other desructive operations are allowed.

## [2.108.2](https://github.com/open-horizon/exchange-api/pull/658) - 2023-02-08
- I18n Updates
- com.github.sbt.sbt-native-packager 1.9.11 -> 1.9.13
- org.postgresql.postgresql 42.5.1 -> 42.5.3
- sbt 1.8.0 -> 1.8.2
- swagger-ui 4.15.0 -> 4.15.5

## [2.108.1](https://github.com/open-horizon/exchange-api/pull/655) - 2023-01-17
- I18n Updates

## [2.108.0](https://github.com/open-horizon/exchange-api/pull/654) - 2022-12-08
- Issue 644: Added POST and DELETE routes for adding and removing a single node assignment to a High Available Node Group.
- Refactored initial HA Group routes.
- Alphabetized messages.txt and associated i18n files.
- Cleaned up some of the swagger file headers.

## [2.107.0](https://github.com/open-horizon/exchange-api/pull/653) - 2022-11-11
- Issue 645: Fixed Node change records create from HA Routes using combination Node IDs (org/node) instead of separate IDs.

## [2.106.0](https://github.com/open-horizon/exchange-api/pull/649) - 2022-11-03
- Dependency Updates.
    - sbt                    1.6.1 -> 1.7.1
    - akka                   2.6.14 -> 2.6.16
    - config                 1.4.0 -> 1.4.2
    - jakarta.ws.rs-api      2.1.1 -> 3.1.0
    - json4s-native          4.0.5 -> 4.0.6
    - json4s-jackson         4.0.5 -> 4.0.6
    - junit                  4.13.1 -> 4.13.2
    - jwt-core               4.3.0 -> 5.0.0
    - postgresql             42.3.4 -> 42.5.0
    - scalacheck             1.15.0-M1 -> 1.17.0
    - slick-pg_json4s        0.20.3 -> 0.20.4
    - swagger-akka-http      2.5.2 -> 2.6.0
    - swagger-core-jakarta   2.1.5 -> 2.1.12
    - swagger-jaxrs2-jakarta 2.1.5 -> 2.1.12
    - swagger-scala-module   1.0.6 -> 2.5.0
    - swagger-ui             4.10.3 -> 4.15.0
    - sbt-scoverage          1.6.1 -> 2.0.6
    - sbt-native-packager    1.8.1 -> 1.9.11
- javax.ws.rs-api support removed.
- jakarta.ws.rs-api support added.

## [2.105.0](https://github.com/open-horizon/exchange-api/pull/635) - 2022-08-18
- Issue 625: Added ability to create hub admins on start-up in config.json
- Issue 625: Added ability to define an account id for the root org on start-up

## [2.104.1](https://github.com/open-horizon/exchange-api/pull/633) - 2022-08-04
- Fixes org.scoverage dependency issues

## [2.104.0](https://github.com/open-horizon/exchange-api/pull/631) - 2022-07-18
- Issue 590: Added Node Group APIs and Test Suites

## [2.103.0](https://github.com/open-horizon/exchange-api/pull/623) - 2022-06-23
- Issue 618: updated Swagger docs for `organization` routes
- Issue 619: fixed date parsing bug in `POST /orgs/{orgid}/changes`
- Issue 620: fixed heartbeat bug in `POST /orgs/{orgid}/changes`
- Issue 621: fixed authorization bug in `POST /orgs/{orgid}/agreements/confirm`

## [2.102.0](https://github.com/open-horizon/exchange-api/pull/617) - 2022-06-08
- Update the Exchange to OpenJDK17 from 11
- Update the Exchange to UBI 9 minimal from 8
- Add TLS 3.0 algorithm `TLS_CHACHA20_POLY1305_SHA256` to the approved algorithms list for TLS connections.
- Updated Sbt to version 1.6.2 from 1.6.1
- Changed GitHub action to use OpenJDK 17 instead of AdoptJDK 11

## [2.101.4](https://github.com/open-horizon/exchange-api/pull/589) - 2022-05-16
- Internationalization updates.

## [2.101.3](https://github.com/open-horizon/exchange-api/pull/588) - 2022-05-11
- Internationalization updates.

## [2.101.2](https://github.com/open-horizon/exchange-api/pull/587) - 2022-05-11
- Internationalization updates.

## [2.101.1](https://github.com/open-horizon/exchange-api/pull/585) - 2022-05-05
- Dependency updates.
    - akka-http  10.2.4 -> 10.2.7
    - akka-http-jackson 1.37.0 -> 1.39.2
    - akka-http-xml 10.2.4 -> 10.2.7
    - akka-http-testkit 10.2.4 -> 10.2.7
    - akka-stream-testkit 2.6.14 -> 2.6.16
    - akka-testkit 2.6.14 -> 2.6.16
    - json4s-native 3.6.6 -> 4.0.5
    - json4s-jackson 3.6.6 -> 4.0.5
    - postgresql 42.2.19 -> 42.3.4
    - scala 2.13.5 -> 2.13.8
    - slf4j-simple 1.7.30 -> 1.7.36
    - slick-pg 0.19.3 -> 0.20.3
    - slick-pg_json4s 0.19.3 -> 0.20.3
    - swagger-akka-http 2.4.2 -> 2.5.2
    - swagger-ui 3.47.1 -> 4.10.3
- New configuration file attributes have been added.
    - akka.http.server.backlog
    - akka.http.server.bind-timeout
    - akka.http.server.idle-timeout
    - akka.http.server.linger-timeout
    - akka.http.server.max-connections
    - akka.http.server.pipelining-limit

## [2.101.0](https://github.com/open-horizon/exchange-api/pull/583) - 2022-04-22
- Issue 581: Array order for versions maintained in the Exchange's DB and returned correctly when retrieved using a GET.
- Issue 582: Nodes, Agbots, Users, and Admin can now read resource changes for AgentFileVersions.

## [2.100.0](https://github.com/open-horizon/exchange-api/pull/580) - 2022-04-18
- Issue 572: All request body values are optional except for `scheduledTime`.
- Updated internationalization.

## [2.99.0](https://github.com/open-horizon/exchange-api/pull/578) - 2022-04-13
- Issue 574: Any Agbot can perform destructive requests. All role types can read.

## [2.98.1](https://github.com/open-horizon/exchange-api/pull/577) - 2022-04-11
- Issue 541: Renamed the directory `\doc` to `\docs`.
- Issue 576: Removed duplicated message key.

## [2.98.0](https://github.com/open-horizon/exchange-api/pull/573) - 2022-04-11
- Issue 571: Response Body Changes For NMP Status GET routes

## [2.97.0](https://github.com/open-horizon/exchange-api/pull/570) - 2022-03-18
- Issue 558: Added AgentFileVersion APIs

## [2.96.0](https://github.com/open-horizon/exchange-api/pull/567) - 2022-03-08
- Issue 566: Fixes GET route schema for NMP Status

## [2.95.0](https://github.com/open-horizon/exchange-api/pull/565) - 2022-02-21
- Issue 557: Added Node Management Policy Status APIs

## [2.94.0](https://github.com/open-horizon/exchange-api/pull/562) - 2022-02-10
- Issue 556: Updated the DB/Http schemas for Node Management Policies and routes.
- Simplified Table Query syntax in the application's source.

## [2.93.0](https://github.com/open-horizon/exchange-api/pull/561) - 2022-02-09
- Issue 560: Organization Administrators can now read all nodes organization wide from `orgs/{orgid}/search/nodes/service`

## [2.92.0](https://github.com/open-horizon/exchange-api/pull/559) - 2022-02-01
- Issue 555: Nodes are now able to read change transactions for Node Management Policies.
- Updated Sbt to version `1.6.1`.

## [2.91.0](https://github.com/open-horizon/exchange-api/pull/554) - 2022-01-19
- Issue 553: Users cannot create/modify/delete node management policies.
- Locked `akka-http-jackson` to version `1.37.0`.

## [2.90.4](https://github.com/open-horizon/exchange-api/pull/552) - 2021-12-27
- Issue 549: Updated Sbt to version `1.6.0`.
- Locked Slick to version `3.3.3`.
- Locked Slick-PG to version `0.19.3`.

## [2.90.3](https://github.com/open-horizon/exchange-api/pull/550) - 2021-12-15
- Issue 549: Updated Sbt to version `1.5.7`.

## [2.90.2](https://github.com/open-horizon/exchange-api/pull/548) - 2021-12-13
- Issue 547: Updated Sbt to version `1.5.6`.

## [2.90.1](https://github.com/open-horizon/exchange-api/pull/545) - 2021-10-29
- Issue 544: error for node policy API when deployment or management attribute is empty

## [2.90.0](https://github.com/open-horizon/exchange-api/pull/543) - 2021-10-28
- Issue 538: New format for node policy APIs

## [2.89.0](https://github.com/open-horizon/exchange-api/pull/542) - 2021-10-25
- Issue 537: Add node management policy

## [2.88.0](https://github.com/open-horizon/exchange-api/pull/534) - 2021-09-14
- No changes, version bump for release.

## [2.87.0](https://github.com/open-horizon/exchange-api/pull/533) - 2021-08-31
- Translation updates

## [2.86.0](https://github.com/open-horizon/exchange-api/pull/530) - 2021-08-27
- Lock the dependency `com.github.swagger-akka-http.swagger-akka-http` to version `2.4.2` to prevent pulling in Akka HTTP version `10.2.6` modules.

## [2.85.0](https://github.com/open-horizon/exchange-api/pull/529) - 2021-08-26
- Translation updates

## [2.84.0](https://github.com/open-horizon/exchange-api/pull/526) - 2021-08-18
- Removed translations in log statements

## [2.83.0](https://github.com/open-horizon/exchange-api/pull/524) - 2021-08-17
- Token validation requirements removed, pending redesign
- Translation updates

## [2.82.0](https://github.com/open-horizon/exchange-api/pull/523) - 2021-08-06
- Token validation requirements added

## [2.81.0](https://github.com/open-horizon/exchange-api/pull/521) - 2021-08-02
- Issue 518 Fix: Updated functionality of `POST /services_configstate` no longer updates version, only filters by it
- New translation files

## [2.80.0](https://github.com/open-horizon/exchange-api/pull/520) - 2021-07-26
- Issue 494: Lower rounds for password hash for node tokens
- Issue 518: Add version to the node registeredServices

## [2.79.0](https://github.com/open-horizon/exchange-api/pull/519) - 2021-07-23
- Issue 517: Fix cases where message intervals are not pulled from the configuration file until after timers are set.

## [2.78.0](https://github.com/open-horizon/exchange-api/pull/516) - 2021-06-28
- Issue 515: Database record trimming is now working again.

## [2.77.0](https://github.com/open-horizon/exchange-api/pull/514) - 2021-06-16
- Removed line comments from Swagger request/response body examples. Preventing proper rendering of the JSON examples.
- Disabled response header Content-Type.

## [2.76.0](https://github.com/open-horizon/exchange-api/pull/510) - 2021-06-09
- Added test cases in PatternSuite and BusinessSuite to ensure secrets are added as expected.

## [2.75.0](https://github.com/open-horizon/exchange-api/pull/511) - 2021-06-01
- Enabled support for TLSv1.2. TLSv1.2 is in support of OpenShift 4.6. The 4.6 HAPoxy router is built on top of RHEL7 which does not support TLSv1.3.

## [2.74.0](https://github.com/open-horizon/exchange-api/pull/501) - 2021-05-19
- Readme Update: Added section on using TLS with the Exchange.

## [2.73.0](https://github.com/open-horizon/exchange-api/pull/502) - 2021-05-17
- Issue 491: Updated the service definition, deployment policy and pattern definitions to support vault based secrets to be used with Open Horizon.

## [2.72.0](https://github.com/open-horizon/exchange-api/pull/500) - 2021-05-07
- Issue 259: Added TLS support to the Exchange.
- Updated Akka: 2.6.10 -> 2.6.14.
- Updated Akka-Http: 10.2.2 -> 10.2.4.
- Updated Swagger UI to version 3.47.1.
- Updated Exchange's Swagger. APIs now sort by Tag -> API -> Method.
- Remade Makefile.

## [2.71.0](https://github.com/open-horizon/exchange-api/pull/493) - 2021-04-21
- Issue 493: Added route GET/orgs/{orgid}/status to fetch org specific information.

## [2.70.0](https://github.com/open-horizon/exchange-api/pull/488) - 2021-04-15
- Fixed issue 423: Upgrade Exchange To Use Akka v2.6.x.
- Also updated sbt, scala, and project dependencies.

## [2.69.0](https://github.com/open-horizon/exchange-api/pull/496) - 2021-04-08
- Added mulitple response headers to all Http responses.
    - `Cache-Control: max-age=0, must-revalidate, no-cache, no-store`
    - `Content-Type: application/json; charset=UTF-8`
    - `X-Content-Type-Options: nosniff`
- Removed `Server` response header from all Http responses.
- Added `application/json` mediatype to all http 200 and 201 responses in the OpenAPI 3.0 Swagger documentation.

## [2.68.0](https://github.com/open-horizon/exchange-api/pull/495) - 2021-04-06
- No changes, version bump.

## [2.67.0](https://github.com/open-horizon/exchange-api/pull/485) - 2021-03-16
- `ApiTime` string methods are wrapped with `fixFormatting()` to remove the issue where seconds or milliseconds get removed

## [2.66.0](https://github.com/open-horizon/exchange-api/pull/483) - 2021-03-12
- HubAdmins now have permission to `READ_ALL_AGBOTS` and `WRITE_ALL_AGBOTS` to facilitate org creation through hzn cli
- Users can no longer be a HubAdmin and an OrgAdmin at the same time
- New translation files
- travis.yml updates

## [2.65.0](https://github.com/open-horizon/exchange-api/pull/477) - 2021-03-09
- Updating `lastUpdated` field of node on `POST /services_configstate` route
- New translation files, and file name fixes
- travis.yml updates

## [2.64.0](https://github.com/open-horizon/exchange-api/pull/475) - 2021-03-04
- Patch added to fix for issue 448. Missed one log message.

## [2.63.0](https://github.com/open-horizon/exchange-api/pull/471) - 2021-03-01
- Fixed issue 418: POST ​/v1​/orgs​/{orgid}​/agbots​/{id}​/agreements​/confirm wrong in swagger
- Added new translation files
- Fixed issue 448: Remove node token from log messages
- Fixed error message id `user.cannot.be.in.root` not found
- Fixed issue 462: Altering SQL to avoid inequalities for checking NF for /changes route for nodes reduces ExchangeDB CPU utilization
- Some progress on issue 451 (the policy /search api)
    - Analyzed the DB query code, adding many comments along the way. Did not find any problems

## [2.62.0](https://github.com/open-horizon/exchange-api/pull/465) - 2021-02-18
- Fixed issue 464: NPE in Exchange on PATCH business policies with incorrect payload returns incorrect HTTP status code - doesn't tell user what is wrong
- Fixed issue 176: When user or org is deleted, delete all corresponding auth cache entries
- Fixed issue 440: Add max parameter on GET /msgs calls

## [2.61.0](https://github.com/open-horizon/exchange-api/pull/463) - 2021-02-12
- Fixed issue 449: check for nodes in auth cache/table first
- Fixed issue 438: Exchange Auth Cache more granular TTL configuration
- Issue 454: Tested and confirmed current behavior is correct when a user and node have the same id
- Fixed issue 436: Hub Admin Bug Fixes/Improvements

## [2.60.0](https://github.com/open-horizon/exchange-api/pull/461) - 2021-02-04
- Issue 456: Change config value `akka.http.server.request-timeout` to `45s`
- Issue 455: Avoid DB reads if `maxMessagesInMailbox` and `maxAgreements` are `0` which is default and means unlimited
- Issue 458: Lower default values of resourceChanges ttl and cleanupInterval

## [2.59.0](https://github.com/open-horizon/exchange-api/pull/460) - 2021-02-03
- Issue 429: add `noheartbeat=true` option to APIs `PUT /orgs/{orgid}/nodes/{nodeid}` and `PUT /orgs/{orgid}/nodes/{nodeid}/agreements/<agreement-id>`
- Issue 419: add `noheartbeat=true` option to `PUT /orgs/{orgid}/nodes/{nodeid}/policy`

## [2.58.0](https://github.com/open-horizon/exchange-api/pull/459) - 2021-02-01
- Issue 445: Add the configState field to the node status API
- Remove `bluehorizon` from the swagger info. (We've decommissioned that site.)

## [2.57.0](https://github.com/open-horizon/exchange-api/pull/457) - 2021-01-30
- Issue 435: Add label and description fields to node and service policy objects

## [2.56.0](https://github.com/open-horizon/exchange-api/pull/426) - 2020-11-10
- Issue 425 - Upgraded the Exchange to Scala 2.13.3

## [2.55.0](https://github.com/open-horizon/exchange-api/pull/424) - 2020-11-09
- Issue 267 - Upgraded the Exchange to Java 11 and SBT 1.4.0. Upgraded Dockerfile to OpenJDK 11. Upgraded Travis CI specification to OpenJDK 11 and Ubuntu 20.04.

## [2.54.0](https://github.com/open-horizon/exchange-api/pull/434) - 2020-10-26
- Issue 427 - Reverting some thread pool changes to version 2.51.0

## [2.53.0](https://github.com/open-horizon/exchange-api/pull/431) - 2020-10-22
- Issue 427 - Added the following connection/thread pool manager settings to the Exchange's configuration json:
    - idleConnectionTestPeriod
    - initialPoolSize
    - maxConnectionAge
    - maxIdleTime
    - maxIdleTimeExcessConnections
    - maxStatementsPerConnection
    - numHelperThreads
    - queueSize
    - testConnectionOnCheckin

## [2.52.0](https://github.com/open-horizon/exchange-api/pull/422) - 2020-10-08
- Issue 421 - Added two OpenAPI 3.0 specification artifacts to `/doc/` project directory. One is for general users and the other is for developers.
- The Exchange's API version number is now properly specified in the default `swagger.json` file the Exchange generates.
- Corrected two test case errors in the `TestBusPolPostSearchRoute` test suite.

## [2.51.0](https://github.com/open-horizon/exchange-api/pull/420) - 2020-10-06
- Issue 387: Removed delay in cleanup of node and agbot msgs. Changed default interval to 30 minutes.

## [2.50.0](https://github.com/open-horizon/exchange-api/pull/416) - 2020-09-25
- Translation Update

## [2.49.0](https://github.com/open-horizon/exchange-api/pull/414) - 2020-09-22
- Issue 413: Table drop order has been reordered and all queries now cascade

## [2.48.0](https://github.com/open-horizon/exchange-api/pull/412) - 2020-09-21
- Issue 410: Changed org maxNodes limit warning message
- Issues 408: Removed duplicates from messages.txt

## [2.47.0](https://github.com/open-horizon/exchange-api/pull/411) - 2020-09-19
- Issue 406: `getUserAccounts` function in IbmCloudModule
- Issues 379 and 369: Authentication pathway for Multitenancy with verifying the org based on the associated account ID

## [2.46.0](https://github.com/open-horizon/exchange-api/pull/409) - 2020-09-18
- Issue 395: `POST /myorgs` route added
- Updated translations

## [2.45.0](https://github.com/open-horizon/exchange-api/pull/405) - 2020-09-16
- Issue 400: Exchange logs org deletions in `resourcechanges` table. Includes dropping orgid foreign key in `resourcechanges` table.
- Issue 400: Exchange always reports org creations to agbots

## [2.44.0](https://github.com/open-horizon/exchange-api/pull/404) - 2020-09-15
- Issue 396: Added routes "GET .../agbots/{agboid}/msgs/{msgid}", and "GET .../nodes/{nodeid}/msgs/{msgid}".

## [2.43.0](https://github.com/open-horizon/exchange-api/pull/403) - 2020-09-11
- Issue 370: Updated messages.txt

## [2.42.0](https://github.com/open-horizon/exchange-api/pull/401) - 2020-09-10
- Added Hub Admin Role and Permissions
- Issue 395: `GET /v1/admin/orgstatus` route added for UI Hub Admin Dashboard
- Limits field added to org resource
- Issue 388: Fixed permissions settings of `/search/nodes/service`
- Issue 392: Fixed issues with org PATCH route

## [2.41.0](https://github.com/open-horizon/exchange-api/pull/398) - 2020-09-02
- Issue 383: Extended route to return node policy details as well.

## [2.40.0](https://github.com/open-horizon/exchange-api/pull/386) - 2020-08-31
- Issue 310 - Post policy search now supports pagination of results. The route is more efficient about dividing work between multiple Agbots.
    - Refactored post pattern search, no pagination added at this time.
    - Refactored policy search test cases. Policy search now has its own test suite.
    - A table for handling pagination offsets an Agbot sessions specific to policy search has been added to the database.

## [2.39.0](https://github.com/open-horizon/exchange-api/pull/394) - 2020-08-31
- Issue 383: Implemented "GET /v1/orgs/{orgid}/node-details" route.

## [2.38.0](https://github.com/open-horizon/exchange-api/pull/385) - 2020-08-07
- Fixed Issue 380: Delete of agbotmsgs and agent msgs by TTL can cause deadlocks with an akka actor running the deletions in a configurable interval

## [2.37.0](https://github.com/open-horizon/exchange-api/pull/382) - 2020-07-28
- Added the environmental variable `$ENVSUBST_CONFIG` to the Dockerfile for controlling the behavior of the utility `envsubst`.
- Changed the default UBI metadata labels for `release` to be the same as `version`, and `vendor` to `Open Horizon`.
- Moved around and rewrote README documentation for `config/exchange-api.tmpl` giving it its own subsection under `Building and Running the Docker Container`.

## [2.36.0](https://github.com/open-horizon/exchange-api/pull/377) - 2020-07-10
- Issue 376: Avoid writing to resourcechanges when msgs are deleted

## [2.35.0](https://github.com/open-horizon/exchange-api/pull/375) - 2020-07-06
- Issue 373: Removed not null constraint on lastheartbeat column in nodes table
- Updated `io.swagger.core.v3` versions specified to remove Jackson Databind incompatibility

## [2.34.0](https://github.com/open-horizon/exchange-api/pull/367) - 2020-06-23
- Issue 365: The PUT /orgs/{orgid}/nodes/{nodeId} route will no longer set/update a node's last heartbeat.

## [2.33.0](https://github.com/open-horizon/exchange-api/pull/356) - 2020-06-17
- Issue 313: Expanded unit testing structure to cover some of the Exchange.
- Added code coverage tool scoverage to the project.
- Added linter tool Scapegoat to the project.
- Open Horizon domain prefix added to system testing package.
- sbt will now pull-down a newer version of plugins if one is available. Defined versions are now the minimum accepted.

## [2.32.0](https://github.com/open-horizon/exchange-api/pull/364) - 2020-06-16
- Validated and updated as needed all request bodies and response bodies on the swagger page.
- Some additional minor swagger bugfixes.
- `swagger-scala-module` back to version `latest.release`

## [2.31.0](https://github.com/open-horizon/exchange-api/pull/362) - 2020-06-10
- Removed "Try it out" buttons from Swagger UI.
- Replaced Swagger parsed examples with custom examples where provided.
- Updated Swagger UI to version 3.26.0.
- Corrected occurrences where Swagger UI parameter names differed from REST API parameter names.
- Added API groupings to Swagger.
- Alpha-numerically sorted API groups in swagger.
- Alpha-numerically sorted REST API in Swagger.
- Swagger groupings show collapsed by default.

## [2.30.0](https://github.com/open-horizon/exchange-api/pull/361) - 2020-05-27
- Updated translations

## [2.29.0](https://github.com/open-horizon/exchange-api/pull/360) - 2020-05-27
- Issue 342: Notification Framework Performance: Added agbot filters for nodemsgs, agbotmsgs, nodestatus, nodeagreements, and agbotagreements

## [2.28.0](https://github.com/open-horizon/exchange-api/pull/359) - 2020-05-19
- Issue 358: Limited user role on `POST /org/{orgid}/search/nodes/error` API to only retrieve nodes self owned.

## [2.27.0](https://github.com/open-horizon/exchange-api/pull/357) - 2020-05-18
- Issue 251: Added `GET /<orgid>/search/nodes/error/all`
- Updated translation files
- Catching duplicate key error on `POST /orgs/{orgid}/users/{username}`

## [2.26.0](https://github.com/open-horizon/exchange-api/pull/355) - 2020-05-14
- Issue 314: Added `GET /catalog/<orgid>/patterns` and `GET /catalog/<orgid>/services`

## [2.25.0](https://github.com/open-horizon/exchange-api/pull/354) - 2020-05-13
- Fixed Issue 330: Postgres error handling

## [2.24.0](https://github.com/open-horizon/exchange-api/pull/353) - 2020-05-07
- Issue 350: Removed `read_all_nodes` from default permissions for `user` role, and removed `read my nodes` from default permissions for `node` role.
- Issue 352: Removed test container. Testing is now done locally to the source code.

## [2.23.0](https://github.com/open-horizon/exchange-api/pull/351) - 2020-05-01
- Issue 346: `connectivity` field in PUT `/nodes/<id>/status` now optional
- Issue 345: Node `lastUpdated` field now updated on node policy and node agreement deletions
- Issue 307: Changed policy property type from `list of string` to `list of strings`

## [2.22.0](https://github.com/open-horizon/exchange-api/pull/349) - 2020-04-30
- Additional Docker labels have been added to the amd64_exchange-api image in compliance with Red Hat certification.
- An Apache version 2.0 license has been added to the amd64_exchange-api image in compliance with Red Hat certification.
- The ability to specify the Exchange API's configuration at container creation has been added.

## [2.21.0](https://github.com/open-horizon/exchange-api/pull/343) - 2020-04-27
- Exchange API now uses Red Hat's Universal Base Image (UBI) 8 Minimal instead of Debian.
- SBT Native Packager updated to version 1.7.0 from 1.5.1.

## [2.20.0](https://github.com/open-horizon/exchange-api/pull/341) - 2020-04-17
- Issue 321: Updates to NodeStatus resource to support edge clusters

## [2.19.0](https://github.com/open-horizon/exchange-api/pull/340) - 2020-04-09
- Issue 320: Expand test suite for Admin routes to include API calls made by non-administer roles.
- Added SBT plugin for Eclipse.

## [2.18.0](https://github.com/open-horizon/exchange-api/pull/334) - 2020-03-31
- Issue 333: AgbotMsgs should not be deleted when nodes are deleted, removed node foreign key in `AgbotMsgs`

## [2.17.0](https://github.com/open-horizon/exchange-api/pull/332) - 2020-03-30
- Issue 295: Notification Framework performance updates
- Issue 324: Notification Framework ChangeId now of type bigint

## [2.16.0](https://github.com/open-horizon/exchange-api/pull/331) - 2020-03-27
- Issue 269: Notification Framework now handles org resource
- Fixed issue 303: Notification Framework Agbot case wasn't automatically checking agbot's org

## [2.15.1](https://github.com/open-horizon/exchange-api/pull/327) - 2020-03-11
- Fixed issue 294: remove no longer used `src/main/webapp`

## [2.15.0](https://github.com/open-horizon/exchange-api/pull/326) - 2020-03-11
- Fixed issue 301: listing all business policies in another org returns 404 instead of 403
- Added field `nodeType` to node resource
- Added fields `clusterDeployment`, and `clusterDeploymentSignature` to service resource
- Return `nodeType` instead of `msgEndPoint` in the pattern and business `/search` routes

## [2.14.0](https://github.com/open-horizon/exchange-api/pull/325) - 2020-03-10
- Issue 311: Notification Framework Agbot Case
- Fixes for Scalatest upgrade to 3.1

## [2.13.0](https://github.com/open-horizon/exchange-api/pull/316) - 2020-03-02
- Issue 312: Using only node table's lastUpdated field to filter on (updating lastUpdated in node, policy, and agreement changes)

## [2.12.3](https://github.com/open-horizon/exchange-api/pull/298) - 2020-02-06
- Fix for `ZonedDateTime.now` truncating seconds and/or milliseconds when they are zero

## [2.12.2](https://github.com/open-horizon/exchange-api/pull/297) - 2020-02-05
- Fixed issue 296: invalid OCP API key was returning 502 instead of the correct 401

## [2.12.1](https://github.com/open-horizon/exchange-api/pull/291) - 2020-02-01
- Temporarily removed the trimming of the `resourcechanges` table

## [2.12.0](https://github.com/open-horizon/exchange-api/pull/290) - 2020-02-01
- Notification Framework: added indices on columns, added sort and limit back to query, added hitMaxRecords boolean field to response

## [2.11.1](https://github.com/open-horizon/exchange-api/pull/289) - 2020-01-31
- Notification Framework: When the db returns an empty response give back the largest changeId from the table

## [2.11.0](https://github.com/open-horizon/exchange-api/pull/288) - 2020-01-31
- Added configurable trimming of the resourcechanges table
- Removed `lastUpdated` filter for most common resourcechanges table query cases
- Added custom akka exception handler to return 502 (instead of 500) for db access errors in the routes
- Added `GET /changes/maxchangeid` route to more efficiently get max changeid during agent initialization

## [2.10.0](https://github.com/open-horizon/exchange-api/pull/286) - 2020-01-29
- Fixed another case for issue 264
- Moved the sort of `/changes` data to exchange scala code (from the postgresql db), and simplified the query filters a little

## [2.9.0](https://github.com/open-horizon/exchange-api/pull/285) - 2020-01-28
- Issue 284: Notification Framework no longer throws an error for empty db responses

## [2.8.0](https://github.com/open-horizon/exchange-api/pull/281) - 2020-01-24
- Issue 278: Notification Framework V1.3 (bug fix of missing changes and increased efficiency)
- Issue 229: Pattern Search "service not in pattern" response fixed

## [2.7.2](https://github.com/open-horizon/exchange-api/pull/282) - 2020-01-24
- Changed the order of the akka route directives to match the path before the http method

## [2.7.1](https://github.com/open-horizon/exchange-api/pull/280) - 2020-01-23
- Fixed the logging of rejections
- Fixed listing all of a resource type from another org
- Added separate way to query icp/ocp exchange org

## [2.7.0](https://github.com/open-horizon/exchange-api/pull/276) - 2020-01-22
- Issue 277: Notification Framework Updates

## [2.6.0](https://github.com/open-horizon/exchange-api/pull/275) - 2020-01-21
- Fixed issue 262 - get icp cluster name once at the beginning
- Fixed issue 256 - trying to access a non-existent resource in another org incorrectly returned 403 instead of 404
- Fixed issue 264 - for auth exceptions, prefer returning retryable http codes
- Verified authentication using OCP
- Modified use of `ICP_EXTERNAL_MGMT_INGRESS` env var so it can optionally have `https://` prepended

## [2.5.0](https://github.com/open-horizon/exchange-api/pull/274) - 2020-01-16
- Made the IP and port the exchange listens on configurable in `config.json`
- Added graceful shutdown, and made wait time for in-flight requests configurable
- Enabled passing args to the exchange svr JVM by setting JAVA_OPTS

## [2.4.0](https://github.com/open-horizon/exchange-api/pull/273) - 2020-01-15
- Switch the db.run() steps to use the akka execution context
- Enabled setting akka config in the exchange `config.json` file

## [2.3.0](https://github.com/open-horizon/exchange-api/pull/271) - 2020-01-14
- Added back in Makefile `test` target for travis
- Updated local postgres config instructions in README.md
- Fixed issue 270: Wrong error response when org not prepended
- Fixed corner-case bug in `POST /orgs/{orgid}/services/{service}/dockauths`

## [2.2.0](https://github.com/open-horizon/exchange-api/pull/268) - 2020-01-10
- Issue 258: Notification Framework bugfixes
- Issue 265: POST /v1/orgs/{orgid}/search/nodes/error now filters on orgid

## [2.1.0](https://github.com/open-horizon/exchange-api/pull/266) - 2020-01-09
- Added `heartbeatIntervals` field to org and node resources
- Fixed exception when route unrecognized

## [2.0.7](https://github.com/open-horizon/exchange-api/pull/263) - 2020-01-08
- Rebased exchange api server to akka-http
- Fixed bugs:
  - In notifcation framework db steps, it didn't check the length of the returned vector before accessing the head
  - Copy/paste bug with service url params
  - Msg for access denied was showing access, instead of requiredaccess
- Removed old functionality:
  - Authorization:Basic header value must now always be base64 encoded
  - The Try it out button on the swagger display is not supported.
  - Being front-ended by a component that does all of the auth (like DataPower) is not supported
  - PUT /orgs/{orgid}/users/{username} to create a new user (use POST instead)
  - Removed POST /admin/loglevel
  - Removed POST /admin/upgradedb
  - Removed POST /admin/downgradedb
  - Removed POST /orgs/{orgid}/search/nodes

## [1.122.0](https://github.com/open-horizon/exchange-api/pull/255) - 2019-12-18
- Implement part 1 of issue 232: add exchange notification system : Resource Changes Route

## [1.121.0](https://github.com/open-horizon/exchange-api/pull/249) - 2019-11-14
- Fix issue 209: Change all occurrences of exchange checking db error msg content
- Fix issue 248: Pattern ID with trailing whitespace allowed

## [1.120.0](https://github.com/open-horizon/exchange-api/pull/242) - 2019-11-07
- Fix issue 213: hzn exchange node update (PATCH) wipes out registeredServices if input bad

## [1.119.0](https://github.com/open-horizon/exchange-api/pull/243) - 2019-11-06
- Implemented issue 239: Remove requirement of token in Node PUT for updates

## [1.118.0](https://github.com/open-horizon/exchange-api/pull/238) - 2019-10-16
- Implemented issue 202: Document using exchange root pw hash in config.json, and support disabling exchange root user
- Improved IAM API key and UI token error handling in IbmCloudModule:getUserInfo()
- Change /admin/hashpw so non-root users can run it

## [1.117.0](https://github.com/open-horizon/exchange-api/pull/235) - 2019-10-11
- Issue 231: Disable exchange org verification for ICP IAM authentication

## [1.116.0](https://github.com/open-horizon/exchange-api/pull/226) - 2019-09-18
- Issue 224: New route `POST /v1/orgs/{orgid}/search/nodes/service` as the previous service search route did not account for IBM services.
- Changes to scale driver for higher scale testing

## [1.115.0](https://github.com/open-horizon/exchange-api/pull/225) - 2019-09-18
- More scale driver streamlining
- Catch a db timeout exception that was surfacing as generic invalid creds

## [1.114.0](https://github.com/open-horizon/exchange-api/pull/223) - 2019-09-17
- Issue 924: Patterns cannot be made with an empty or nonexistent services field.
- Removed redundant `messages_en.txt` file as the default file is in English.

## [1.113.0](https://github.com/open-horizon/exchange-api/pull/222) - 2019-09-16
- Added fast hash to cached token/pw
- Invalidate id cache entry when authentication fails
- Re-implemented scale drivers in go
- Changed scale node.go and agbot.go logic to have agbot create node msgs each time it finds it in the search
- In the scale drivers added some more retryable errors, and made retry max and sleep configurable

## [1.112.0](https://github.com/open-horizon/exchange-api/pull/220) - 2019-09-15
- Issue 214: Add optional `arch` field to body of pattern search so the search filters only on the arch passed in

## [1.111.0](https://github.com/open-horizon/exchange-api/pull/219) - 2019-09-14
- New exchange search API to return how many nodes a particular service is currently running on
- Added pii translation files in `src/main/resources`

## [1.110.0](https://github.com/open-horizon/exchange-api/pull/218) - 2019-09-13
- Put the 2 cache mechanisms in the same branch/build, that can be chosen via the config file

## [1.109.0](https://github.com/open-horizon/exchange-api/pull/215) - 2019-09-12
- Implemented issue 187: Add API Route to search for nodes that have errors

## [1.108.0](https://github.com/open-horizon/exchange-api/pull/212) - 2019-09-10
- Fixed issue 171: Improve way exchange icp auth gets org to verify it (ICP 3.2.1 authentication of UI token (getting cluster name))

## [1.107.0](https://github.com/open-horizon/exchange-api/pull/211) - 2019-09-07
- Implemented issue 204: made `nodes/{id}/errors` more flexible so anax can add whatever fields they want

## [1.106.0](https://github.com/open-horizon/exchange-api/pull/208) - 2019-09-06
- Fixed issue 207: Change use of icp-management-ingress DNS name for ICP 3.2.1 (also works in 3.2.0)
- Fixed issue 183: Exchange node not found for POST /msgs should return 404, not 500 (also fixed it for agbot msgs)
- Fixed issue 185: serviceVersions should never be empty
- Fixed issue anax 783: Allow setting the pattern for a node in the exchange (error handling)

## [1.105.0](https://github.com/open-horizon/exchange-api/pull/206) - 2019-09-05
- Msg file fixes
- Upgradedb updates
- Have scale scripts calculate the number of agreements each node HB

## [1.103.0](https://github.com/open-horizon/exchange-api/pull/200) - 2019-08-28
- Moved exchange messages into separate non-code files to enable translation

## [1.102.0](https://github.com/open-horizon/exchange-api/pull/189) - 2019-08-27
- New exchange resource for node errors

## [1.101.0](https://github.com/open-horizon/exchange-api/pull/182) - 2019-08-07
- Optimize exchange pattern search for nodes to filter on node arch
- Incorporated Sadiyah's analysis of exchange logs into the perf/scaling test drivers

## [1.100.0](https://github.com/open-horizon/exchange-api/pull/178) - 2019-07-20
- Moved business policy nodehealth defaults to config.json and made them longer
- Modified perf/scaling scripts to work with ICP
- Moved pattern nodehealth defaults to config.json and made them longer

## [1.98.0](https://github.com/open-horizon/exchange-api/pull/177) - 2019-07-18
- Fixed timing problem in public cloud api key authentication
- Added `scale/node.sh` and `scale/agbot.sh` performance/scale test drivers
- Added retries to IBM IAM API authentication calls
- Added additional api methods tested in `scale/test.sh`, and improved the error handling

## [1.96.0](https://github.com/open-horizon/exchange-api/pull/170) - 2019-06-20
- Switched to use internal ICP authentication endpoints
- Caught exception when no auth provided

## [1.95.0](https://github.com/open-horizon/exchange-api/pull/168) - 2019-06-19
- Creating a business policy no longer rejects an arch of `""`
- Updated scale tests

## [1.94.0](https://github.com/open-horizon/exchange-api/pull/166) - 2019-06-19
- Added verification of org during ICP IAM authentication

## [1.93.0](https://github.com/open-horizon/exchange-api/pull/162) - 2019-06-14
- Added check in pattern POST/PUT for service version not being set (anax issue 932)
- Removed user pw from debug level logging
- Added old tables to table drop list for /admin/dropdb, in case they were running with an old db
- Merged in fixes to exchange travis test (issue 88)

## [1.92.0](https://github.com/open-horizon/exchange-api/pull/159) - 2019-06-14
- Added updatedBy field to user resource in exchange

## [1.91.0](https://github.com/open-horizon/exchange-api/pull/156) - 2019-06-12
- Change requiredServices.version to requiredServices.versionRange

## [1.90.0](https://github.com/open-horizon/exchange-api/pull/155) - 2019-06-11
- Have business policy node search also filter on arch (issue 139)
- Check for update time of node agreement and node policy in exchange business policy search (issue 146)
- Ensure a customer can't set their own org to orgType IBM (only root can)

## [1.89.0](https://github.com/open-horizon/exchange-api/pull/154) - 2019-06-11
- Disallow public patterns in non-IBM orgs in the exchange
- Add date to exchange logging

## [1.88.0](https://github.com/open-horizon/exchange-api/pull/152) - 2019-06-10
- Added checking for valid service references in the userInput sections in patterns, business policies, and nodes
- Allowed admin user to change pw of other user in org, and allowed root user to change any pw

## [1.87.0](https://github.com/open-horizon/exchange-api/pull/151) - 2019-06-07
- Changed `userInput` field of patterns and business policies to include service info
- Added `userInput` field to nodes

## [1.86.0](https://github.com/open-horizon/exchange-api/pull/150) - 2019-06-09
- Fixed ExchConfigSuites so it works with default config.json for `make test` in travis

## [1.85.0](https://github.com/open-horizon/exchange-api/pull/149) - 2019-06-06
- Added use of ICP self-signed cert

## [1.84.0](https://github.com/open-horizon/exchange-api/pull/145) - 2019-05-23
- Added `userInput` section to pattern resource
- Added `userInput` section to business policy resource

## [1.83.0](https://github.com/open-horizon/exchange-api/pull/144) - 2019-05-22
- Add `arch` field to node

## [1.82.0](https://github.com/open-horizon/exchange-api/pull/138) - 2019-05-17
- Log all invalid credentials
- Removed resource object
- Added business policies to agbot
- Added node search api for business policies

## [1.81.0](https://github.com/open-horizon/exchange-api/pull/136) - 2019-05-13
- Fixed bug in initdb and dropdb for service and business policy tables

## [1.80.0](https://github.com/open-horizon/exchange-api/pull/135) - 2019-05-10
- Add ICP IAM auth via platform key for `hzn`

## [1.79.0](https://github.com/open-horizon/exchange-api/pull/134) - 2019-05-09
- Added iamtoken support for ICP
- Fixed cloud env vars in primedb.sh to be more general
- Fixed OneNodeProperty bug in NodeRoutes and NodesSuite

## [1.78.0](https://github.com/open-horizon/exchange-api/pull/132) - 2019-05-08
- Add Makefile and README support for building docker images for a branch
- Add business policy resource

## [1.77.0](https://github.com/open-horizon/exchange-api/pull/128) - 2019-05-01
- Fix swagger doc for node id
- Fixed bug: when org doesn't exist error msg is database-timeout
- Added running primedb.sh to README.md
- Add node and service policy resources

## [1.76.0](https://github.com/open-horizon/exchange-api/pull/119) - 2019-04-11
- Do not show keystore encoded password in log
- Do not return "invalid creds" when can't reach db

## [1.75.0](https://github.com/open-horizon/exchange-api/pull/116) - 2019-02-28
- Configure https/ssl at run time (instead of build time), so the same container can run with both http and https, or just http.
- Increase db auth access timeout to avoid timeouts under certain conditions.

## [1.74.0](https://github.com/open-horizon/exchange-api/pull/115) - 2019-02-27
- Add TLS/SSL support to jetty, instead of relying on the front end (e.g. haproxy) to terminate the SSL
- Increase default max number of resources by an order of magnitude

## [1.73.0](https://github.com/open-horizon/exchange-api/pull/113) - 2019-02-21
- Update scale test driver
- Support composite service url in `POST /orgs/{orgid}/patterns/{pattern}/search`
- Support composite service url in `/orgs/{orgid}/search/nodes`
- add field to org called orgType, which will have value IBM for any of our orgs that have ibm services in them
- allow any user to GET all orgs with filter of orgType=IBM
- delete support for the special `public` org
- fix bug in which in the access denied error msg it only reported the generic access needed

## [1.71.0](https://github.com/open-horizon/exchange-api/pull/112) - 2019-02-13
- POST /orgs/{orgid}/nodes/{id}/configstate to POST /orgs/{orgid}/nodes/{id}/services_configstate

## [1.70.0](https://github.com/open-horizon/exchange-api/pull/111) - 2019-02-12
- In node resource, make msgEndPoint and softwareVersions optional
- Add node post to be able to be able to update some of the configState attrs
- Remove Resource from swagger and Services

## [1.69.0](https://github.com/open-horizon/exchange-api/pull/109) - 2019-02-07
- Support authentication via ibm cloud iam token that comes from the web ui

## [1.68.0](https://github.com/open-horizon/exchange-api/pull/108) - 2019-02-06
- Fix support for multiple orgs associate with 1 ibm cloud account

## [1.67.0](https://github.com/open-horizon/exchange-api/pull/107) - 2019-01-29
- Make orgs/ibmcloud_id table column not need to be unique, to support multiple orgs for the same ibm cloud account
- Fix problem with spaces in creds (issue 90)

## [1.66.0](https://github.com/open-horizon/exchange-api/pull/102) - 2018-11-28
- Handled special case of getting your own user when using iamapikey

## [1.65.0](https://github.com/open-horizon/exchange-api/pull/101) - 2018-11-23
- Added better errors for ibm auth
- Added /admin/clearAuthCaches
- Added IBM Cloud auth automated tests

## [1.64.0](https://github.com/open-horizon/exchange-api/pull/99) - 2018-11-16
- Added IBM Cloud auth plugin

## [1.63.0](https://github.com/open-horizon/exchange-api/pull/96) - 2018-11-13
- Fixed swagger
- Cleaned up README.md
- Improved error in `POST/PUT/PATCH pattern` when reference service does not exist to output service org, url, arch, version range
- Added optional `documentation` field to service resource
- Added new `resource` definition that can be required by services to hold things like models
- Added `singleton` as a valid `sharable` value
- Added JAAS authentication framework

## [1.62.0](https://github.com/open-horizon/exchange-api/pull/89) - 2018-11-02
- Remove microservice, workload, and blockchain support (leaving just service support)
- Remove deprecated `PUT /orgs/{orgid}/agbots/{id}/patterns/{patid}` method (use POST instead)

## [1.61.0](https://github.com/open-horizon/exchange-api/pull/87) - 2018-10-11
- Support IAM API keys for bx cr access by adding `username` field to service dockauths (issue anax 651)

## [1.60.0](https://github.com/open-horizon/exchange-api/pull/83) - 2018-10-05
- Fixed creds in url parms when root creds are specified or when /orgs is queried (exchange-api issue 77)
- Fixed creds in url parms from 1 org querying a resource in another org (exchange-api issue 58)

## [1.59.0](https://github.com/open-horizon/exchange-api/pull/82) - 2018-10-04
- Add nodeOrgids to body of `/org/{orgid}/patterns/{pat-id}/nodehealth`

## [1.58.0](https://github.com/open-horizon/exchange-api/pull/81) - 2018-09-27
- Make some more fields in POST/PUT patterns and services optional.

## [1.57.0](https://github.com/open-horizon/exchange-api/pull/80) - 2018-09-26
- Support wildcard pattern in `POST /orgs/{orgid}/agbots/{id}/patterns` body

## [1.56.0](https://github.com/open-horizon/exchange-api/pull/79) - 2018-09-26
- `PUT /orgs/{orgid}/agbots/{id}/patterns/{patid}`: add nodeOrgid to the body
- `POST /orgs/{orgid}/patterns/{pattern}/search`: add a list of nodeOrgid to the body

## [1.55.0](https://github.com/open-horizon/exchange-api/pull/72) - 2018-04-16
- Automatically run /admin/initdb or /admin/upgradedb on startup

## [1.54.0](https://github.com/open-horizon/exchange-api/pull/71) - 2018-04-16
- Added `agreementLess` attribute to `services` section of pattern

## [1.53.0](https://github.com/open-horizon/exchange-api/pull/68) - 2018-04-11
- If POST /services/{svcid}/dockerauths is a dup, just update the current one
- Moved docker image to docker hub openhorizon

## [1.52.0](https://github.com/open-horizon/exchange-api/pull/67) - 2018-04-09
- Add services sub-resource called dockerauths for docker image auth tokens

## [1.51.0](https://github.com/open-horizon/exchange-api/pull/65) - 2018-04-07
- Fixed error upgrading db schema from earlier than db schema 3

## [1.50.0](https://github.com/open-horizon/exchange-api/pull/63) - 2018-04-03
- Fix build error in Schema.scala in wiotp enviroment

## [1.49.0](https://github.com/open-horizon/exchange-api/pull/62) - 2018-03-20
- allow IBM agbots to access private ms/wl/svc/pat via get /workloads etc.
- explicitly set http response code of 200 for successful GETs to avoid occasional 404's

## [1.48.0](https://github.com/open-horizon/exchange-api/pull/60) - 2018-03-12
- clean up variables in nodes suite tests
- add back in a few tests for workloads and microservices in pattern and node search
- pattern node search api now validates the serviceUrl
- change agbot agreement to service
- change put node status to service
- change name of service package field to imageStore
- test built image with compose db and verify exchange version output

## [1.47.0](https://github.com/open-horizon/exchange-api/pull/59) - 2018-03-08
### External changes
- change node search to service
- change node, node agreement, pattern search
- changed pattern to support service
### Limitations in This Version
- Agbot agreement resources still refer to workloadOrgid, workloadPattern, workloadUrl (but you can put service info in those fields, because they are not checked)
- Node status resources still refer to workloads and microservices (but i think you can put service info in those fields, because i don't think they are checked)

## [1.46.0](https://github.com/open-horizon/exchange-api/pull/56) - 2018-02-15
### External changes
- added keys to ms, wk, service, pattern (anax issue 498)
- Figured out how to set the Response codes in swagger
- Fixed the swagger 2 level resource problem

## [1.45.0](https://github.com/open-horizon/exchange-api/pull/55) - 2018-02-08
### External changes
- fixed some library versions to make the build work again
- added service resource (issue #51)
- detect if svc requires other svc with different arch

## [1.44.0](https://github.com/open-horizon/exchange-api/pull/54) - 2018-01-04
### External changes
- Allowed missing or empty torrent field in microservice and workload

## [1.43.0](https://github.com/open-horizon/exchange-api/pull/53) - 2017-12-05
### External changes
- Fixed swagger

## [1.42.0](https://github.com/open-horizon/exchange-api/pull/52) - 2017-11-29
### External changes
- Allowed node in other org to access agbot in IBM org

## [1.41.0](https://github.com/open-horizon/exchange-api/pull/48) - 2017-10-25
### External changes
- When creating/updating a workload and verifying the existence of the apiSpecs, it now treats the MS versions as ranges
- (No need to upgrade the db if coming from version 1.38 or later, altho it won't hurt either)
### Todos left to be finished in subsequent versions
- If maxAgreements>1, for CS, in search don't return node to agbot if agbot from same org already has agreement for same workload.
- Add api for wiotp to get number of devices and agreements
- Allow random PW creation for user creation
- Add ability to change owner of node
- Add patch capability for node registered microservices?
- Add an unauthenticated admin status rest api
- Change local automated tests in Makefile to be more consistent with travis ci
- Figure out how to set "Response Class (Status 200)" in swagger
- See if there is a way to fix the swagger hack for 2 level resources
- Consider changing all creates to POST, and update (via put/patch) return codes to 200
- Any other schema changes?

## [1.40.0](https://github.com/open-horizon/exchange-api/pull/46) - 2017-10-19
- Added GET /admin/version

## [1.39.0](https://github.com/open-horizon/exchange-api/pull/44) - 2017-10-10
### External changes
- Updated jetty version to 9.4.7 and fixed build to pull latest bug fixes in the 9.4 range
- Fixed non-pattern node search to not find pattern nodes
- Now filter both pattern and non-pattern node searches to not return nodes with empty publicKey values
- Added `"nodeHealth": { "missing_heartbeat_interval": 600, "check_agreement_status": 120 }` policy to patterns (it is a peer to the dataVerification field). Existing pattern resources in the DB will be converted on the way out. New POST/PUTs must include this new field.
- Added POST /orgs/{orgid}/patterns/{patid}/nodehealth and POST /orgs/{orgid}/search/nodehealth for agbot to get node lastHeartbeat and agreement status

(No need to upgrade the db if coming from version 1.38, altho it won't hurt either)

## [1.38.0](https://github.com/open-horizon/exchange-api/pull/43) - 2017-10-09
### External changes
- Added PUT/GET/DELETE /orgs/{id}/agbots/{id}/patterns/{id} and removed the patterns field from the agbot resource. This allows each org/pattern pair of an agbot to be managed individually.
- If you are coming from exchange versions 1.35 - 37, you can run POST /admin/upgradedb (which preserves the data). If coming from an earlier version, you must drop all of the tables and run POST /admin/initdb.

## [1.37.0](https://github.com/open-horizon/exchange-api/pull/42) - 2017-10-06
### External changes
- Added PUT/GET/DELETE /orgs/{id}/nodes/{id}/status
- If you are coming from v1.35.0 or v1.36.0, you can run POST /admin/upgradedb (which preserves the data). If coming from an earlier version, you must drop all of the tables and run POST /admin/initdb.

## [1.36.0](https://github.com/open-horizon/exchange-api/pull/40) - 2017-10-05
### External changes
- Allow queries in another org at the /microservices, /workloads, /patterns, /bctypes/{id}/blockchains level (and only return the public resources)
- Added 'public' as a filter attribute for microservices, workloads, patterns.
- Removed old/unused sections api.specRef, api.objStoreTmpls, and api.microservices from config.json
- Replaced empty {} return from PUT nodes/{nodeid} with normal {"code": 201, "msg": "node <id> added or updated"}
- Supported config.json api.limits.* equal to 0 to mean unlimited

## [1.35.0](https://github.com/open-horizon/exchange-api/pull/38) - 2017-09-27
- Fixed bug in which users, agbots, and nodes couldn't read their own org

## [1.34.0](https://github.com/open-horizon/exchange-api/pull/37) - 2017-09-27
### Limitations
- Need to dropdb and initdb, and re-enter data
### External changes
- Fixed bug: https://github.com/open-horizon/exchange-api/issues/35
- Enabled IBM agbots to read all patterns, search for nodes with patterns in other orgs, and msg nodes in other orgs (and they can msg it back)
- Enabled identities in other orgs to read all public patterns/workloads/microservices/blockchains
- Fix bug: Put root/root in the db, so foreign keys work correctly
- Now automatically create a public org and allow anonymous to create user in that org
- Ensured that a user can't elevate himself to an admin user
- Added schema table to store the current db schema and changed /admin/upgradedb to use that to upgrade correctly from any version. In this version of the exchange you still need to dropdb/initdb, but for all subsequent versions you should be able to use /admin/upgradedb.
- Quick fix to not require a slash in the node pattern attribute if it is blank


## [1.32.0](https://github.com/open-horizon/exchange-api/pull/36) - 2017-09-22
### Limitations
- Need to dropdb and initdb, and re-enter data
### External Incompatible changes
- Added pattern field to node resource
- Move arch field 1 level up in pattern schema, and renamed it to workloadArch
- Implemented /org/{orgid}/patterns/{pat-id}/search

## [1.31.0](https://github.com/open-horizon/exchange-api/pull/34) - 2017-09-20
### Limitations
- Need to dropdb and initdb, and re-enter data
### External Incompatible changes
- Removed the option on PUT users/{username} to set password or email to blank to mean don't update it. Implemented PATCH users/{username} instead.
- Changed config.json acls READ_MY_ORGS and WRITE_MY_ORGS to their singular versions
- Added 'admin' field to user, enabled admin users to do everything in their org (including create other users)
- Updated the pattern resource schema to more explicitly support multiple different workloads, and multiple versions of the same workload (see swagger)
- Added deployment_overrides and deployment_overrides_signature to patterns.workloads
- Added check_rate (int) in the dataVerification

## [1.29.0](https://github.com/open-horizon/exchange-api/pull/33) - 2017-09-19
### Limitations
- Need to dropdb and initdb, and re-enter data
- Cross-org access only works with root for now
- Before nodes, agbots, microservices, workloads, or patterns are created via front end auth, you have to create the user (and then use that person-type auth) so the owner foreign key succeeds
### External Incompatible changes
- Users, nodes, and agbots can now read their own org resource
- Renamed devices to nodes
- Removed properties, counterPartyProperties, microservices, maxAgreements from pattern resource
- Nodes can now read their own /org/{orgid} resource
- Moved bctype and blockchain under /org/{orgid} and added public field
- Modified PUT orgs/{org}/nodes/{id}/agreements/{agreementid} to include the pattern and workload, and to accept "microservices":[{"url":"ms url","org":"myorg"}] instead of an array of url strings like it is now.
- Modified PUT orgs/{org}/agbots/{id}/agreements/{agreementid} to accept workload orgid, pattern, and url, instead of just the url
- Added list of orgs/patterns to agbot resource
- A new setting `"frontEndHeader": "issuer"` is required in the `root` section of config.json to use data power in front of exchange
### Internal things done in this version
- Tested dropdb with older compose db
- Modified tests to have each suite use its own org
- Added support for data power headers

## [1.28.0](https://github.com/open-horizon/exchange-api/pull/32) - 2017-09-14
### Limitations
- Need to dropdb and initdb, and re-enter data
### External Incompatible changes
- All resource except /admin are now under /org/{orgid}. By convention we will use "IBM" for our orgid.
- All identities must be prefixed by "{orgid}/". E.g. IBM/myuser, IBM/mydeviceid, IBM/myagbotid (as a special case, the root user is root/root). This includes identities listed in owner and definedBy fields.
- Added 'public' field to microservices and workloads
- Change returned http code to 404 when there are no resource found for GET /org/{orgid}/users, /org/{orgid}/devices, /org/{orgid}/agbots
- Anonymous POST user/{username} (to create user) and POST user/{username}/reset are no longer supported (because we can't let them do that in any org)
- This rare case is no longer supported: no id is specified in the credentials and it defaults to the device/agbot id in the url
### Done in this version
- Added the /orgs/{orgid} resource
- Moved under /orgs/{orgid}: users, devices, agbots, microservices, workloads
- Updated ACL to only only access within your org (with a few exceptions)
- Added 'public' field to microservices and workloads
- Got automated tests to pass with pre-existing IBM org

## [1.27.0](https://github.com/open-horizon/exchange-api/pull/31) - 2017-09-08
- Added org resource (still need to implement ACLs for it and move other resources under it)
- Changed PUT /devices/{id}/agreements/{agid} to accept list of microservices (and corresponding GET and POST search changes)

## [1.26.0](https://github.com/open-horizon/exchange-api/pull/24) - 2017-07-08
- Fixed bug in which the CORS header Access-Control-Allow-Origin was not set in the response

## [1.25.0](https://github.com/open-horizon/exchange-api/pull/22) - 2017-06-20
- Added /microservices and /workloads resources
- Changed default permissions to allow devices and agbots to read all agbots
- Changed additional image tag from latest to volcanostaging

## [1.24.0](https://github.com/open-horizon/exchange-api/pull/13) - 2017-02-21
- Converted to the newer build.sbt file and upgraded to sbt version 0.13.13
- Migrated to version 9.4.x of jetty
- Made ACLs read from config.json (instead of hardcoded)
- Fixed bug: with a brand new db, could not run POST /admin/initdb because root user was not defined.
- Switched the dockerfile FROM statement to the official jetty image
- Added msgs, bctypes, and blockchains to the scale test script
- Removed requirement for MS numAgreements be set to 1.

## [1.23.0](https://github.com/open-horizon/exchange-api/pull/9) - 2017-02-13
- Added PUT/GET/DELETE /bctypes/{type} and PUT/GET/DELETE /bctypes/{type}/blockchains/{name} to store/retrieve BC info
- All DELETE methods not return 404 if the resource was not found. (Before it was inconsistent, some returned 204 and some 404.)
- Refactored authentication and authorization code, so now it is easier to read, easier to extend, and handles a few corner cases better. No external changes for all the mainline use cases.
- Changed the scala version used in building the container to be the latest stable and the same as my dev environment (2.12.1)
- Moved up to latest stable version of scalatra (2.4.1 ot 2.5.1)
- Access denied msgs now give details
- Fixed pw reset email to give link to the swagger entry to change your pw with the token

## [1.22.0](https://github.com/open-horizon/exchange-api/pull/2) - 2017-02-01
- Added DELETE /devices/{id}/agreements and DELETE /agbots/{id}/agreements to delete all of the agreements of a device/agbot
- Added GET /(devices|agbots)/{id}?attribute={attrname} to get a single attribute of the device/agbot resource
- Added PATCH /(devices|agbots)/{id} to set a single attribute of the device/agbot resource
- Modified PUT /devices/{id} and PUT /agbots/{id} to also take publicKey in the input body (but made it backward compatible so you do not have to specify it)
- Added POST devices/{device-id}/msgs (to send/create a msg), GET devices/{device-id}/msgs (to read your msgs), and DELETE devices/{device-id}/msgs/{msg-id}. Also added the same methods for agbots.
- Fixed POST /admin/dropdb to not delete the root user from the authentication cache, so you have credentials to run POST /admin/initdb
- Improved the implementation of POST /search/devices. All externals are the same, except now if all devices are stale it returns 404 (the json returned is still `{"devices":[],"lastIndex":0}`)
- Added GET /admin/status to use for monitoring the exchange api svr
- Added checks so users can not create more devices, agbots, agreements, or msgs than the max numbers allowed in config.json, and added the associated maxMessagesInMailbox in the default config.json
- Removed all code for the in-memory db (it was not being maintained anyway)
- Added logging a real client IP (gotten from haproxy)

## [1.21.0](https://github.com/open-horizon/exchange-api/pull/1) - 2017-01-12
- adjusted debug logging to be more helpful and less verbose
- fixed POST admin/migratedb

## 1.20.0
- added support for base64 encoded credentials in the header
- added optional filter params to GET /devices and /agbots
- now log who each rest api call is from
- root user is now saved in the persistent db (but still loaded/updated from config.json at startup)
- deleting a user will now delete all of its devices, agbots, and agreements (we now have a proper foreign key between users and devices/agbots, and we use the sql on delete cascade option, so now the db completely takes care of object referencial integrity)

## 1.19.0
- added fallback for getting device/agbot owner from the db during authentication
- automated tests can now use environment variables to run against a different exchange svr
- split the create part of PUT /users/{u} to POST /users/{u} so it can be rate limited. (Except PUT can still be used to creates users as root.)
- fixed but that didn't let POST /users/{u}/reset be called with no creds
- fixed schema of users table to have username be the primary key

## 1.18.0
- Added support for putting token, but not id, in URL parms

## 1.17.0
- Fixed bug when user creds are passed into url parms
- Increased timeouts when comparing auth cache to db, and temporarily fall back to cached

## 1.16.0
- Added synchronization (locking) around the auth cache

## 1.15.0
- DB persistence complete. Remove `api.db.memoryDb` from your config.json to use (the default is to use it). Specifically in this update:
  - Fixed some of the /users routes in the persistence case
  - Modified the auth cache to verify with the db when running in persistence mode (still a few changes needed for agbots)
  - Added error handling to /users routes in persistence mode
  - Got UsersSuite tests working for persistence (the device and agbot calls within there are still skipped in persistence mode)
  - Added persistence for all /devices routes, including transactions where needed
  - Added persistence for all /agbots routes, including transactions where needed
- Added POST /agreements/confirm
- Added config.json flag to disable getting microservices templates (because that part is a little slow). In this case, PUT /devices/{id} just returns an empty map `{}`
- DELETE /devices/{id} and /agbots/{id} now delete their agreements too

## 1.14.0
- Changed field daysStale to secondsStale in POST /search/devices. **This is not a backward compatible change.**
- Caught json parsing exceptions so they don't directly get returned to the client.
- Finished db persistence for GET /devices and /devices/{id}

## 1.13.0
- Added POST /agbots/{agid}/dataheartbeat and POST /agbots/{agid}/isrecentdata (experimental)
- Added POST /admin/initdb and /admin/dropdb to create the db schema. Still experimenting about whether we want these.
- Added POST /admin/loglevel to change the logging level on the fly
- Fixed bug: users and id's were not getting removed from the auth cache when they were deleted from the db.
- Fixed bug: an agreement for 1 MS of a device prevented the other MS from being returned in POST /search/devices
- Must for now set `db.memoryDb: true` in config.json file, like this:

```
{
  "api": {
    "db": {
      "memoryDb": true
    }
  }
}
```

## 1.12.0
- Fixed poor error msg when root email in config.json is blank
- Added in-memory cache of non-hashed pw/tokens. Increased speed by more than 10x.
- (No external changes in this version AFAIK)

## 1.11.0
- added scale/performance tests
- added a softwareVersions section of input to PUT /devices/{id} so devices can report their sw levels to the exchange. See swagger for details.
- POST /users/{username}/reset is now implemented and will email a timed pw reset token to the user's email address
- Made the logging level configurable via the config.json file

## 1.10.0
- Now it hashes all of the passwords and tokens before storing them in the db
- Add 2 /admin routes that can only be run as root: reload (reload the config file) and hashpw (return a hash the given pw)
- Added support for the root pw in the config file to be a hashed value
- To run the automated tests you must now create a local config file with the root pw in it (see above)
- Straightened out some of the Makefile variables and dependencies

## 1.9.0
- Added support for using an osgi version range in POST /search/devices
- Made GET / return html that lists available exchange services (so far just a link to the api swagger info)
- Fixed bug where PUT /devices/{id} created the device in the db even if the microservice url was wrong such that micro templates could not be returned
- Made the microservice url input to PUT /devices/{id} tolerant of a trailing /
- Added support for /etc/horizon/exchange/config.json. If that config file doesn't exist, the server will still run with default values appropriate for local development.
- Updated all of the bash test scripts to use a real microservice url.

## 1.8.0
- PUT /devices/{id} now returns a hash with keys of specRef and values of microservice template json blob

## 1.7.0
- Fixed bug: agbot couldn't run POST /search/devices
- Fixed a few other, more minor, access problems
- Updated swagger info to clarify who can run what

## 1.6.0
