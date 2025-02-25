# Changelog

All notable changes to this project will be documented in this file.

## [2.124.1](https://github.com/open-horizon/exchange-api/pull/750) - 2025-02-25
- Backporting cherry-picked changes for configuration, dependencies, the Dockerfile, and GitHub workflows from the following.
  - [PR#749](https://github.com/open-horizon/exchange-api/pull/749)
    - GitHub workflows
  - v2.126.1 [PR#747](https://github.com/open-horizon/exchange-api/pull/747)
    - Default configuration
  - [PR#745](https://github.com/open-horizon/exchange-api/pull/745)
    - Dockerfile
  - [PR#743](https://github.com/open-horizon/exchange-api/pull/743)
    - GitHub workflows
  - [PR#725](https://github.com/open-horizon/exchange-api/pull/725)
    - Dependencies
  - v2.125.0 [PR#720](https://github.com/open-horizon/exchange-api/pull/720)
    - Scala version

## [2.124.0] - 2024-09-14
- Application configuration overhaul.
  - Some database configuration changes are not backwards compatible. 
- GET methods for Node resources no longer return passwords for admin user types, unless directly owned.
- Added new rest paths for deployment patterns and policies aligning and clarifying these resources.
  - `.../v1/orgs/<organization>/deployment/patterns/...`
  - `.../v1/orgs/<organization>/deployment/policies/...`

## [2.123.0] - 2024-04-19
- pekko-http-xml 1.0.0 -> 1.0.1
- Reorganized class references in the Swagger documentation generator.

## [2.122.0] - 2024-01-23
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

## [2.121.0] - 2023-11-16
- Restructured the source for Catalog, Deployment Patterns and Policies, Management Policies, Nodes, Node Groups, and Services.

## [2.120.0] - 2023-10-18
- Restructured the source for Users, Services, Organizations, and some of the search routes.
- Corrected a few Swagger documentation errors.
- Sbt 1.9.2 -> 1.9.6

## [2.119.0] - 2023-07-26
- Issue 692: Added new attribute `enableNodeLevelSecrets` to `secretBinding` for Deployment Patterns and Policies.
- Reorganized utility and auth source objects.
- Renamed a few test source packages.

## [2.118.0] - 2023-07-25
- Rebuilt ../orgs/<org>/changes route.
- Reorganized the majority of table source.
- Fixed error message typo.

## [2.117.0] - 2023-07-13
- Issue 686: Added rest parameter `manifest` to Get /v1/orgs/<org>/managementpolicies route.

## [2.116.0] - 2023-07-11
- Issue 676: Added `isNamespaceScoped` attribute to Nodes.
- Reorganized table package object source for Nodes and related.
- SBT 1.9.0 -> 1.9.2

## [2.115.3] - 2023-07-07
- Issue 675: Node tokens can be changed by all User types, and public keys can only be [un]set without changing keys by Nodes.

## [2.115.2] - 2023-07-07
- Issue 675: Removed extra regular expressions modifying searched Service's URL.

## [2.115.1] - 2023-07-06
- Issue 675: Fixed User and Node access to searching all Patterns and Services within the Node's Organization.
- Corrected error messages.

## [2.115.0] - 2023-06-28
- Issue 675: Patch /orgs/<organization>/nodes/<node> No longer allows setting the Node's token if a public key is set.
    - Added a new database table to handle Service querying and matching.
- pattern and userInput are now User archetype authorization scoped.
- Reorganized the Node resource source.
- postgresql           42.5.3 -> 42.6.0
- SBT                  1.8.2  -> 1.9.0
- swagger-scala-module 2.8.2  -> 2.11.0
- Swagger UI           4.15.5 -> 5.1.0

## [2.114.0] - 2023-05-01
- Issue 639: Moved top-level attribute clusterNamespace to a sub-attribute under service for Deployment Policies.

## [2.113.0] - 2023-04-26
- Issue 637 - Added CORS support. Can be configurated via the Exchange's config.json file.
- Changed Java package path to the prefix org.openhorizon.
- Moved source handling routing to new sub-package org.openhorizon.route..
    - AdminRoutes and AgbotRoutes have been further sub-divided based on rest resource.
- Deprecated custom methods for handling Akka configuration parameters in favor of framework defaults.
    - Backwards compatibility is still maintained.

## [2.112.0] - 2023-04-07
- Issue 640: Added `clusterNamespace` attribute to Deployment Patterns.
- Issue 641: Added `clusterNamespace` attribute to Nodes.

## [2.111.0] - 2023-04-03
- Issue 639: Added `clusterNamespace` attribute to Deployment Policies.

## [2.110.1] - 2023-03-01
- Issue 662: Slick's schema.createIfNotExists function is not creating foreign keys. Reverting back to the simple create function.

## [2.110.0] - 2023-02-28
- Issue 662:
    - Removed database schema change that was added in error.
    - Reworked the schema upgrade function to fully roll back to the schema version at boot when encountering an error.

## [2.109.2] - 2023-02-23
- Issue 657: Added additional ddl clauses to the Exchange's schema upgrade path.

## [2.109.1] - 2023-02-23
- Issue 657: Removed erroneous quotes around column name to be added to database.

## [2.109.0] - 20230-02-21
- Issue 657: Restricted Users from adding Nodes generally to any available Node Group. To add a Node to a Node Group that Node Group must be empty, or only have the User's Nodes in the Group. Added a flag for Node Groups that were created by Organization Admins. Users may only remove their owned nodes from these groups, no other desructive operations are allowed.

## [2.108.2] - 2023-02-08
- I18n Updates
- com.github.sbt.sbt-native-packager 1.9.11 -> 1.9.13
- org.postgresql.postgresql 42.5.1 -> 42.5.3
- sbt 1.8.0 -> 1.8.2
- swagger-ui 4.15.0 -> 4.15.5

## [2.108.1]
- I18n Updates

## [2.108.0]
- Issue 644: Added POST and DELETE routes for adding and removing a single node assignment to a High Available Node Group.
- Refactored initial HA Group routes.
- Alphabetized messages.txt and associated i18n files.
- Cleaned up some of the swagger file headers.

## [2.107.0]
- Issue 645: Fixed Node change records create from HA Routes using combination Node IDs (org/node) instead of separate IDs.

## [2.106.0]
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

## [2.105.0]
- Issue 625: Added ability to create hub admins on start-up in config.json
- Issue 625: Added ability to define an account id for the root org on start-up

## [2.104.1]
- Fixes org.scoverage dependency issues

## [2.104.0]
- Issue 590: Added Node Group APIs and Test Suites


## [2.103.0]
- Issue 618: updated Swagger docs for `organization` routes
- Issue 619: fixed date parsing bug in `POST /orgs/{orgid}/changes`
- Issue 620: fixed heartbeat bug in `POST /orgs/{orgid}/changes`
- Issue 621: fixed authorization bug in `POST /orgs/{orgid}/agreements/confirm`

## [2.102.0]
- Update the Exchange to OpenJDK17 from 11
- Update the Exchange to UBI 9 minimal from 8
- Add TLS 3.0 algorithm `TLS_CHACHA20_POLY1305_SHA256` to the approved algorithms list for TLS connections.
- Updated Sbt to version 1.6.2 from 1.6.1
- Changed GitHub action to use OpenJDK 17 instead of AdoptJDK 11

## [2.101.4]
- Internationalization updates.

## [2.101.3]
- Internationalization updates.

## [2.101.2]
- Internationalization updates.

## [2.101.1]
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

## [2.101.0]
- Issue 581: Array order for versions maintained in the Exchange's DB and returned correctly when retrieved using a GET.
- Issue 582: Nodes, Agbots, Users, and Admin can now read resource changes for AgentFileVersions.

## [2.100.0]
- Issue 572: All request body values are optional except for `scheduledTime`.
- Updated internationalization.

## [2.99.0]
- Issue 574: Any Agbot can perform destructive requests. All role types can read.

## [2.98.1]
- Issue 541: Renamed the directory `\doc` to `\docs`.
- Issue 576: Removed duplicated message key.

## [2.98.0]
- Issue 571: Response Body Changes For NMP Status GET routes

## [2.97.0]
- Issue 558: Added AgentFileVersion APIs

## [2.96.0]
- Issue 566: Fixes GET route schema for NMP Status

## [2.95.0]
- Issue 557: Added Node Management Policy Status APIs

## [2.94.0]
- Issue 556: Updated the DB/Http schemas for Node Management Policies and routes.
- Simplified Table Query syntax in the application's source.

## [2.93.0]
- Issue 560: Organization Administrators can now read all nodes organization wide from `orgs/{orgid}/search/nodes/service`

## [2.92.0]
- Issue 555: Nodes are now able to read change transactions for Node Management Policies.
- Updated Sbt to version `1.6.1`.

## [2.91.0]
- Issue 553: Users cannot create/modify/delete node management policies.
- Locked `akka-http-jackson` to version `1.37.0`.

## [2.90.4]
- Issue 549: Updated Sbt to version `1.6.0`.
- Locked Slick to version `3.3.3`.
- Locked Slick-PG to version `0.19.3`.

## [2.90.3]
- Issue 549: Updated Sbt to version `1.5.7`.

## [2.90.2]
- Issue 547: Updated Sbt to version `1.5.6`.

## [2.90.1]
- Issue 544: error for node policy API when deployment or management attribute is empty

## [2.90.0]
- Issue 538: New format for node policy APIs

## [2.89.0]
- Issue 537: Add node management policy

## [2.88.0]
- No changes, version bump for release.

## [2.87.0]
- Translation updates

## [2.86.0]
- Lock the dependency `com.github.swagger-akka-http.swagger-akka-http` to version `2.4.2` to prevent pulling in Akka HTTP version `10.2.6` modules.

## [2.85.0]
- Translation updates

## [2.84.0]
- Removed translations in log statements

## [2.83.0]
- Token validation requirements removed, pending redesign
- Translation updates

## [2.82.0]
- Token validation requirements added

## [2.81.0]
- Issue 518 Fix: Updated functionality of `POST /services_configstate` no longer updates version, only filters by it
- New translation files

## [2.80.0]
- Issue 494: Lower rounds for password hash for node tokens
- Issue 518: Add version to the node registeredServices

## [2.79.0]
- Issue 517: Fix cases where message intervals are not pulled from the configuration file until after timers are set.

## [2.78.0]
- Issue 515: Database record trimming is now working again.

## [2.77.0]
- Removed line comments from Swagger request/response body examples. Preventing proper rendering of the JSON examples.
- Disabled response header Content-Type.

## [2.76.0]
- Added test cases in PatternSuite and BusinessSuite to ensure secrets are added as expected.

## [2.75.0]
- Enabled support for TLSv1.2. TLSv1.2 is in support of OpenShift 4.6. The 4.6 HAPoxy router is built on top of RHEL7 which does not support TLSv1.3.

## [2.74.0]
- Readme Update: Added section on using TLS with the Exchange.

## [2.73.0]
- Issue 491: Updated the service definition, deployment policy and pattern definitions to support vault based secrets to be used with Open Horizon.

## [2.72.0]
- Issue 259: Added TLS support to the Exchange.
- Updated Akka: 2.6.10 -> 2.6.14.
- Updated Akka-Http: 10.2.2 -> 10.2.4.
- Updated Swagger UI to version 3.47.1.
- Updated Exchange's Swagger. APIs now sort by Tag -> API -> Method.
- Remade Makefile.

## [2.71.0]
- Issue 493: Added route GET/orgs/{orgid}/status to fetch org specific information.

## [2.70.0]
- Fixed issue 423: Upgrade Exchange To Use Akka v2.6.x.
- Also updated sbt, scala, and project dependencies.

## [2.69.0]
- Added mulitple response headers to all Http responses.
    - `Cache-Control: max-age=0, must-revalidate, no-cache, no-store`
    - `Content-Type: application/json; charset=UTF-8`
    - `X-Content-Type-Options: nosniff`
- Removed `Server` response header from all Http responses.
- Added `application/json` mediatype to all http 200 and 201 responses in the OpenAPI 3.0 Swagger documentation.

## [2.68.0]
- No changes, version bump.

## [2.67.0]
- `ApiTime` string methods are wrapped with `fixFormatting()` to remove the issue where seconds or milliseconds get removed

## [2.66.0]
- HubAdmins now have permission to `READ_ALL_AGBOTS` and `WRITE_ALL_AGBOTS` to facilitate org creation through hzn cli
- Users can no longer be a HubAdmin and an OrgAdmin at the same time
- New translation files
- travis.yml updates

## [2.65.0]
- Updating `lastUpdated` field of node on `POST /services_configstate` route
- New translation files, and file name fixes
- travis.yml updates

## [2.64.0]
- Patch added to fix for issue 448. Missed one log message.

## [2.63.0]
- Fixed issue 418: POST ​/v1​/orgs​/{orgid}​/agbots​/{id}​/agreements​/confirm wrong in swagger
- Added new translation files
- Fixed issue 448: Remove node token from log messages
- Fixed error message id `user.cannot.be.in.root` not found
- Fixed issue 462: Altering SQL to avoid inequalities for checking NF for /changes route for nodes reduces ExchangeDB CPU utilization
- Some progress on issue 451 (the policy /search api)
    - Analyzed the DB query code, adding many comments along the way. Did not find any problems

## [2.62.0]
- Fixed issue 464: NPE in Exchange on PATCH business policies with incorrect payload returns incorrect HTTP status code - doesn't tell user what is wrong
- Fixed issue 176: When user or org is deleted, delete all corresponding auth cache entries
- Fixed issue 440: Add max parameter on GET /msgs calls

## [2.61.0]

- Fixed issue 449: check for nodes in auth cache/table first
- Fixed issue 438: Exchange Auth Cache more granular TTL configuration
- Issue 454: Tested and confirmed current behavior is correct when a user and node have the same id
- Fixed issue 436: Hub Admin Bug Fixes/Improvements

## [2.60.0]

- Issue 456: Change config value `akka.http.server.request-timeout` to `45s`
- Issue 455: Avoid DB reads if `maxMessagesInMailbox` and `maxAgreements` are `0` which is default and means unlimited
- Issue 458: Lower default values of resourceChanges ttl and cleanupInterval

## [2.59.0]

- Issue 429: add `noheartbeat=true` option to APIs `PUT /orgs/{orgid}/nodes/{nodeid}` and `PUT /orgs/{orgid}/nodes/{nodeid}/agreements/<agreement-id>`
- Issue 419: add `noheartbeat=true` option to `PUT /orgs/{orgid}/nodes/{nodeid}/policy`

## [2.58.0]

- Issue 445: Add the configState field to the node status API
- Remove `bluehorizon` from the swagger info. (We've decommissioned that site.)

## [2.57.0]

- Issue 435: Add label and description fields to node and service policy objects

## [2.56.0]

- Issue 425 - Upgraded the Exchange to Scala 2.13.3

## [2.55.0]

- Issue 267 - Upgraded the Exchange to Java 11 and SBT 1.4.0. Upgraded Dockerfile to OpenJDK 11. Upgraded Travis CI specification to OpenJDK 11 and Ubuntu 20.04.

## [2.54.0]

- Issue 427 - Reverting some thread pool changes to version 2.51.0

## [2.53.0]

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

## [2.52.0]

- Issue 421 - Added two OpenAPI 3.0 specification artifacts to `/doc/` project directory. One is for general users and the other is for developers.
- The Exchange's API version number is now properly specified in the default `swagger.json` file the Exchange generates.
- Corrected two test case errors in the `TestBusPolPostSearchRoute` test suite.

## [2.51.0]

- Issue 387: Removed delay in cleanup of node and agbot msgs. Changed default interval to 30 minutes.

## [2.50.0]

- Translation Update

## [2.49.0]

- Issue 413: Table drop order has been reordered and all queries now cascade

## [2.48.0]
- Issue 410: Changed org maxNodes limit warning message
- Issues 408: Removed duplicates from messages.txt

## [2.47.0]
- Issue 406: `getUserAccounts` function in IbmCloudModule
- Issues 379 and 369: Authentication pathway for Multitenancy with verifying the org based on the associated account ID

## [2.46.0]
- Issue 395: `POST /myorgs` route added
- Updated translations

## [2.45.0]
- Issue 400: Exchange logs org deletions in `resourcechanges` table. Includes dropping orgid foreign key in `resourcechanges` table.
- Issue 400: Exchange always reports org creations to agbots

## [2.44.0]

- Issue 396: Added routes "GET .../agbots/{agboid}/msgs/{msgid}", and "GET .../nodes/{nodeid}/msgs/{msgid}".

## [2.43.0]
- Issue 370: Updated messages.txt

## [2.42.0]

- Added Hub Admin Role and Permissions
- Issue 395: `GET /v1/admin/orgstatus` route added for UI Hub Admin Dashboard
- Limits field added to org resource
- Issue 388: Fixed permissions settings of `/search/nodes/service`
- Issue 392: Fixed issues with org PATCH route

## [2.41.0]

- Issue 383: Extended route to return node policy details as well.

## [2.40.0]

- Issue 310 - Post policy search now supports pagination of results. The route is more efficient about dividing work between multiple Agbots.
    - Refactored post pattern search, no pagination added at this time.
    - Refactored policy search test cases. Policy search now has its own test suite.
    - A table for handling pagination offsets an Agbot sessions specific to policy search has been added to the database.

## [2.39.0]

- Issue 383: Implemented "GET /v1/orgs/{orgid}/node-details" route.

## [2.38.0]

- Fixed Issue 380: Delete of agbotmsgs and agent msgs by TTL can cause deadlocks with an akka actor running the deletions in a configurable interval

## [2.37.0]

- Added the environmental variable `$ENVSUBST_CONFIG` to the Dockerfile for controlling the behavior of the utility `envsubst`.
- Changed the default UBI metadata labels for `release` to be the same as `version`, and `vendor` to `Open Horizon`.
- Moved around and rewrote README documentation for `config/exchange-api.tmpl` giving it its own subsection under `Building and Running the Docker Container`.

## [2.36.0]

- Issue 376: Avoid writing to resourcechanges when msgs are deleted

## [2.35.0]

- Issue 373: Removed not null constraint on lastheartbeat column in nodes table
- Updated `io.swagger.core.v3` versions specified to remove Jackson Databind incompatibility

## [2.34.0]

- Issue 365: The PUT /orgs/{orgid}/nodes/{nodeId} route will no longer set/update a node's last heartbeat.

## [2.33.0]

- Issue 313: Expanded unit testing structure to cover some of the Exchange.
- Added code coverage tool scoverage to the project.
- Added linter tool Scapegoat to the project.
- Open Horizon domain prefix added to system testing package.
- sbt will now pull-down a newer version of plugins if one is available. Defined versions are now the minimum accepted.

## [2.32.0]

- Validated and updated as needed all request bodies and response bodies on the swagger page.
- Some additional minor swagger bugfixes.
- `swagger-scala-module` back to version `latest.release`

## [2.31.0]

- Removed "Try it out" buttons from Swagger UI.
- Replaced Swagger parsed examples with custom examples where provided.
- Updated Swagger UI to version 3.26.0.
- Corrected occurrences where Swagger UI parameter names differed from REST API parameter names.
- Added API groupings to Swagger.
- Alpha-numerically sorted API groups in swagger.
- Alpha-numerically sorted REST API in Swagger.
- Swagger groupings show collapsed by default.

## [2.30.0]

- Updated translations

## [2.29.0]

- Issue 342: Notification Framework Performance: Added agbot filters for nodemsgs, agbotmsgs, nodestatus, nodeagreements, and agbotagreements

## [2.28.0]

- Issue 358: Limited user role on `POST /org/{orgid}/search/nodes/error` API to only retrieve nodes self owned.

## [2.27.0]

- Issue 251: Added `GET /<orgid>/search/nodes/error/all`
- Updated translation files
- Catching duplicate key error on `POST /orgs/{orgid}/users/{username}`

## [2.26.0]

- Issue 314: Added `GET /catalog/<orgid>/patterns` and `GET /catalog/<orgid>/services`

## [2.25.0]

- Fixed Issue 330: Postgres error handling

## [2.24.0]

- Issue 350: Removed `read_all_nodes` from default permissions for `user` role, and removed `read my nodes` from default permissions for `node` role.
- Issue 352: Removed test container. Testing is now done locally to the source code.

## [2.23.0]

- Issue 346: `connectivity` field in PUT `/nodes/<id>/status` now optional
- Issue 345: Node `lastUpdated` field now updated on node policy and node agreement deletions
- Issue 307: Changed policy property type from `list of string` to `list of strings`

## [2.22.0]

- Additional Docker labels have been added to the amd64_exchange-api image in compliance with Red Hat certification.
- An Apache version 2.0 license has been added to the amd64_exchange-api image in compliance with Red Hat certification.
- The ability to specify the Exchange API's configuration at container creation has been added.

## [2.21.0]

- Exchange API now uses Red Hat's Universal Base Image (UBI) 8 Minimal instead of Debian.
- SBT Native Packager updated to version 1.7.0 from 1.5.1.

## [2.20.0]

- Issue 321: Updates to NodeStatus resource to support edge clusters

## [2.19.0]

- Issue 320: Expand test suite for Admin routes to include API calls made by non-administer roles.
- Added SBT plugin for Eclipse.

## [2.18.0]

- Issue 333: AgbotMsgs should not be deleted when nodes are deleted, removed node foreign key in `AgbotMsgs`

## [2.17.0]

- Issue 295: Notification Framework performance updates
- Issue 324: Notification Framework ChangeId now of type bigint

## [2.16.0]

- Issue 269: Notification Framework now handles org resource
- Fixed issue 303: Notification Framework Agbot case wasn't automatically checking agbot's org

## [2.15.1]

- Fixed issue 294: remove no longer used `src/main/webapp`

## [2.15.0]

- Fixed issue 301: listing all business policies in another org returns 404 instead of 403
- Added field `nodeType` to node resource
- Added fields `clusterDeployment`, and `clusterDeploymentSignature` to service resource
- Return `nodeType` instead of `msgEndPoint` in the pattern and business `/search` routes

## [2.14.0]

- Issue 311: Notification Framework Agbot Case
- Fixes for Scalatest upgrade to 3.1

## [2.13.0]

- Issue 312: Using only node table's lastUpdated field to filter on (updating lastUpdated in node, policy, and agreement changes)

## [2.12.3]

- Fix for `ZonedDateTime.now` truncating seconds and/or milliseconds when they are zero

## [2.12.2]

- Fixed issue 296: invalid OCP API key was returning 502 instead of the correct 401

## [2.12.1]

- Temporarily removed the trimming of the `resourcechanges` table

## [2.12.0]

- Notification Framework: added indices on columns, added sort and limit back to query, added hitMaxRecords boolean field to response

## [2.11.1]

- Notification Framework: When the db returns an empty response give back the largest changeId from the table

## [2.11.0]

- Added configurable trimming of the resourcechanges table
- Removed `lastUpdated` filter for most common resourcechanges table query cases
- Added custom akka exception handler to return 502 (instead of 500) for db access errors in the routes
- Added `GET /changes/maxchangeid` route to more efficiently get max changeid during agent initialization

## [2.10.0]

- Fixed another case for issue 264
- Moved the sort of `/changes` data to exchange scala code (from the postgresql db), and simplified the query filters a little

## [2.9.0]

- Issue 284: Notification Framework no longer throws an error for empty db responses

## [2.8.0]

- Issue 278: Notification Framework V1.3 (bug fix of missing changes and increased efficiency)
- Issue 229: Pattern Search "service not in pattern" response fixed

## [2.7.2]

- Changed the order of the akka route directives to match the path before the http method

## [2.7.1]

- Fixed the logging of rejections
- Fixed listing all of a resource type from another org
- Added separate way to query icp/ocp exchange org

## [2.7.0]

- Issue 277: Notification Framework Updates

## [2.6.0]

- Fixed issue 262 - get icp cluster name once at the beginning
- Fixed issue 256 - trying to access a non-existent resource in another org incorrectly returned 403 instead of 404
- Fixed issue 264 - for auth exceptions, prefer returning retryable http codes
- Verified authentication using OCP
- Modified use of `ICP_EXTERNAL_MGMT_INGRESS` env var so it can optionally have `https://` prepended

## [2.5.0]

- Made the IP and port the exchange listens on configurable in `config.json`
- Added graceful shutdown, and made wait time for in-flight requests configurable
- Enabled passing args to the exchange svr JVM by setting JAVA_OPTS

## [2.4.0]

- Switch the db.run() steps to use the akka execution context
- Enabled setting akka config in the exchange `config.json` file

## [2.3.0]

- Added back in Makefile `test` target for travis
- Updated local postgres config instructions in README.md
- Fixed issue 270: Wrong error response when org not prepended
- Fixed corner-case bug in `POST /orgs/{orgid}/services/{service}/dockauths`

## [2.2.0]

- Issue 258: Notification Framework bugfixes
- Issue 265: POST /v1/orgs/{orgid}/search/nodes/error now filters on orgid

## [2.1.0]

- Added `heartbeatIntervals` field to org and node resources
- Fixed exception when route unrecognized

## [2.0.x]

- Rebased exchange api server to akka-http
- Fixed bugs:
    - In notification framework db steps, it didn't check the length of the returned vector before accessing the head
    - Copy/paste bug with service url params
    - Msg for access denied was showing access, instead of required access
- Removed old functionality:
    - Authorization:Basic header value must now always be base64 encoded
    - The Try it out button on the swagger display is not supported.
    - Being front-ended by a component that does all of the auth (like DataPower) is not supported
    - PUT /orgs/{orgid}/users/{username} to create a new user (use POST instead)
    - Removed POST /admin/loglevel
    - Removed POST /admin/upgradedb
    - Removed POST /admin/downgradedb
    - Removed POST /orgs/{orgid}/search/nodes

## [1.122.0]

- Implement part 1 of issue 232: add exchange notification system : Resource Changes Route

## [1.121.0]

- Fix issue 209: Change all occurrences of exchange checking db error msg content
- Fix issue 248: Pattern ID with trailing whitespace allowed

## [1.120.0]

- Fix issue 213: hzn exchange node update (PATCH) wipes out registeredServices if input bad

## [1.119.0]

- Implemented issue 239: Remove requirement of token in Node PUT for updates

## [1.118.0]

- Implemented issue 202: Document using exchange root pw hash in config.json, and support disabling exchange root user
- Improved IAM API key and UI token error handling in IbmCloudModule:getUserInfo()
- Change /admin/hashpw so non-root users can run it

## [1.117.0]

- Issue 231: Disable exchange org verification for ICP IAM authentication

## [1.116.0]

- Issue 224: New route `POST /v1/orgs/{orgid}/search/nodes/service` as the previous service search route did not account for IBM services.
- Changes to scale driver for higher scale testing

## [1.115.0]

- More scale driver streamlining
- Catch a db timeout exception that was surfacing as generic invalid creds

## [1.114.0]

- Issue 924: Patterns cannot be made with an empty or nonexistent services field.
- Removed redundant `messages_en.txt` file as the default file is in English.

## [1.113.0]

- Added fast hash to cached token/pw
- Invalidate id cache entry when authentication fails
- Re-implemented scale drivers in go
- Changed scale node.go and agbot.go logic to have agbot create node msgs each time it finds it in the search
- In the scale drivers added some more retryable errors, and made retry max and sleep configurable

## [1.112.0]

- Issue 214: Add optional `arch` field to body of pattern search so the search filters only on the arch passed in

## [1.111.0]

- New exchange search API to return how many nodes a particular service is currently running on
- Added pii translation files in `src/main/resources`

## [1.110.0]

- Put the 2 cache mechanisms in the same branch/build, that can be chosen via the config file

## [1.109.0]

- Implemented issue 187: Add API Route to search for nodes that have errors

## [1.108.0]

- Fixed issue 171: Improve way exchange icp auth gets org to verify it (ICP 3.2.1 authentication of UI token (getting cluster name))

## [1.107.2] (built only in the auth-cache2 branch so far)

- Fixed auth exception being thrown for missing msg in msg file

## [1.107.1] (built only in the auth-cache2 branch so far)

- Implemented issue 187: Add API Route to search for nodes that have errors

## [1.107.0]

- Implemented issue 204: made `nodes/{id}/errors` more flexible so anax can add whatever fields they want

## [1.106.0]

- Fixed issue 207: Change use of icp-management-ingress DNS name for ICP 3.2.1 (also works in 3.2.0)
- Fixed issue 183: Exchange node not found for POST /msgs should return 404, not 500 (also fixed it for agbot msgs)
- Fixed issue 185: serviceVersions should never be empty
- Fixed issue anax 783: Allow setting the pattern for a node in the exchange (error handling)

## [1.105.0]

- Msg file fixes
- Upgradedb updates
- Have scale scripts calculate the number of agreements each node HB

## [1.103.0]

- Moved exchange messages into separate non-code files to enable translation

## [1.102.0]

- New exchange resource for node errors

## [1.101.0]

- Optimize exchange pattern search for nodes to filter on node arch
- Incorporated Sadiyah's analysis of exchange logs into the perf/scaling test drivers

## [1.100.0]

- Moved business policy nodehealth defaults to config.json and made them longer
- Modified perf/scaling scripts to work with ICP

## [1.99.0]

- Moved pattern nodehealth defaults to config.json and made them longer

## [1.98.0]

- Fixed timing problem in public cloud api key authentication
- Added `scale/node.sh` and `scale/agbot.sh` performance/scale test drivers

## [1.97.0]

- Added retries to IBM IAM API authentication calls
- Added additional api methods tested in `scale/test.sh`, and improved the error handling

## [1.96.0]

- Switched to use internal ICP authentication endpoints
- Caught exception when no auth provided

## [1.95.0]

- Creating a business policy no longer rejects an arch of `""`
- Updated scale tests

## [1.94.0]

- Added verification of org during ICP IAM authentication

## [1.93.0]

- Added check in pattern POST/PUT for service version not being set (anax issue 932)
- Removed user pw from debug level logging
- Added old tables to table drop list for /admin/dropdb, in case they were running with an old db
- Merged in fixes to exchange travis test (issue 88)

## [1.92.0]

- Added updatedBy field to user resource in exchange

## [1.91.0]

- Change requiredServices.version to requiredServices.versionRange

## [1.90.0]

- Have business policy node search also filter on arch (issue 139)
- Check for update time of node agreement and node policy in exchange business policy search (issue 146)
- Ensure a customer can't set their own org to orgType IBM (only root can)

## [1.89.0]

- Disallow public patterns in non-IBM orgs in the exchange
- Add date to exchange logging

## [1.88.0]

- Added checking for valid service references in the userInput sections in patterns, business policies, and nodes
- Allowed admin user to change pw of other user in org, and allowed root user to change any pw

## [1.87.0]

- Changed `userInput` field of patterns and business policies to include service info
- Added `userInput` field to nodes

## [1.86.0]

- Fixed ExchConfigSuites so it works with default config.json for `make test` in travis

## [1.85.0]

- Added use of ICP self-signed cert

## [1.84.0]

- Added `userInput` section to pattern resource
- Added `userInput` section to business policy resource

## [1.83.0]

- Add `arch` field to node

## [1.82.0]

- Log all invalid credentials
- Removed resource object
- Added business policies to agbot
- Added node search api for business policies

## [1.81.0]

- Fixed bug in initdb and dropdb for service and business policy tables

## [1.80.0]

- Add ICP IAM auth via platform key for `hzn`

## [1.79.0]

- Added iamtoken support for ICP
- Fixed cloud env vars in primedb.sh to be more general
- Fixed OneNodeProperty bug in NodeRoutes and NodesSuite

## [1.78.0]

- Add Makefile and README support for building docker images for a branch
- Add business policy resource

## [1.77.0]

- Fix swagger doc for node id
- Fixed bug: when org doesn't exist error msg is database-timeout
- Added running primedb.sh to README.md
- Add node and service policy resources

## [1.76.0]

- Do not show keystore encoded password in log
- Do not return "invalid creds" when can't reach db

## [1.75.0]

- Configure https/ssl at run time (instead of build time), so the same container can run with both http and https, or just http.
- Increase db auth access timeout to avoid timeouts under certain conditions.

## [1.74.0]

- Add TLS/SSL support to jetty, instead of relying on the front end (e.g. haproxy) to terminate the SSL
- Increase default max number of resources by an order of magnitude

## [1.73.0]

- Update scale test driver
- Support composite service url in `POST /orgs/{orgid}/patterns/{pattern}/search`
- Support composite service url in `/orgs/{orgid}/search/nodes`

## [1.72.0]

- add field to org called orgType, which will have value IBM for any of our orgs that have ibm services in them
- allow any user to GET all orgs with filter of orgType=IBM
- delete support for the special `public` org
- fix bug in which in the access denied error msg it only reported the generic access needed

## [1.71.0]

- POST /orgs/{orgid}/nodes/{id}/configstate to POST /orgs/{orgid}/nodes/{id}/services_configstate

## [1.70.0]

- In node resource, make msgEndPoint and softwareVersions optional
- Add node post to be able to be able to update some of the configState attrs
- Remove Resource from swagger and Services

## [1.69.0]

- Support authentication via ibm cloud iam token that comes from the web ui

## [1.68.0]

- Fix support for multiple orgs associate with 1 ibm cloud account

## [1.67.0]

- Make orgs/ibmcloud_id table column not need to be unique, to support multiple orgs for the same ibm cloud account
- Fix problem with spaces in creds (issue 90)

## [1.66.0]

- Handled special case of getting your own user when using iamapikey

## [1.65.0]

- Added better errors for ibm auth
- Added /admin/clearAuthCaches
- Added IBM Cloud auth automated tests

## [1.64.0]

- Added IBM Cloud auth plugin

## [1.63.0]

- Fixed swagger
- Cleaned up README.md
- Improved error in `POST/PUT/PATCH pattern` when reference service does not exist to output service org, url, arch, version range
- Added optional `documentation` field to service resource
- Added new `resource` definition that can be required by services to hold things like models
- Added `singleton` as a valid `sharable` value
- Added JAAS authentication framework
