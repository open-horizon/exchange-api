# Horizon Data Exchange Server and REST API

The data exchange API provides API services for the exchange web UI (future), the edge nodes, and agreement Bots.

The exchange service also provides a few key services for BH for areas in which the decentralized P2P tools
do not scale well enough yet. As soon as the decentralized tools are sufficient, they will replace these
services in the exchange.

## Preconditions for Local Development

- [Install scala](http://www.scala-lang.org/download/install.html)
- [Install sbt](https://www.scala-sbt.org/1.x/docs/Setup.html)
- (optional) Install conscript and giter8 if you want to get example code from scalatra.org
- Install postgresql locally (unless you have a remote instance you are using). Instructions for installing on Mac OS X:
    - Install: `brew install postgresql`
    - Note: when running/testing the exchange svr in a docker container, it can't reach you postgres instance on `localhost`, so configure it to also listen on your local IP:
      - set this to your IP: `export MY_IP=<my-ip>`
      - `echo "host all all $MY_IP/32 trust" >> /usr/local/var/postgres/pg_hba.conf`
      - `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = '$MY_IP'/" /usr/local/var/postgres/postgresql.conf`
      - `brew services start postgresql` or if it is already running `brew services restart postgresql`
    - Or if your test machine is on a private subnet:
      - trust all clients on your subnet: `echo 'host all all 192.168.1.0/24 trust' >> /usr/local/var/postgres/pg_hba.conf`
      - listen on all interfaces: `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" /usr/local/var/postgres/postgresql.conf`
      - `brew services start postgresql` or if it is already running `brew services restart postgresql`
    - Test: `psql "host=$MY_IP dbname=postgres user=<myuser> password=''"`
- Add a config file on your development system at /etc/horizon/exchange/config.json with at least the following content (this is needed for the automated tests. Defaults and the full list of config variables are in `src/main/resources/config.json`):

```
{
	"api": {
		"db": {
			"jdbcUrl": "jdbc:postgresql://localhost/postgres",		// my local postgres db
			"user": "myuser",
			"password": ""
		},
		"logging": {
			"level": "DEBUG"
		},
		"root": {
			"password": "myrootpw"
		}
	}
}
```
- If you want to run the `FrontEndSuite` test class `config.json` should also include `"frontEndHeader": "issuer"` directly after `email` under `root`.
- Set the same exchange root password in your shell environment, for example:
```
export EXCHANGE_ROOTPW=myrootpw
```

- If someone hasn't done it already, create the TLS private key and certificate:
```
export EXCHANGE_KEY_PW=<pass-phrase>
make gen-key
```

- Otherwise, get files `exchangecert.pem`, `keypassword`, and `keystore` from the person who created them and put them in `./keys/etc`.

## Building and Running in Local Sandbox

- `sbt`
- `~reStart`
- Once the server starts, to see the swagger output, browse: [http://localhost:8080/v1/swagger](http://localhost:8080/v1/swagger)
- To try a simple rest method curl: `curl -X GET "http://localhost:8080/v1/admin/version"`. You should get the exchange version number as the response.  
- A convenience script `src/test/bash/primedb.sh` can be run to prime the DB with some exchange resources to use in manually testing:
```
export EXCHANGE_USER=<my-user-in-IBM-org>
export EXCHANGE_PW=<my-pw-in-IBM-org>
src/test/bash/primedb.sh
```
- `primedb.sh` will only create what doesn't already exist, so it can be run again to restore some resources you have deleted.
- To locally test the exchange against an existing ICP cluster:
```
export ICP_EXTERNAL_MGMT_INGRESS=<icp-external-host>:8443
```

## Tips on Using Sbt

When at the `sbt` sub-command prompt:

- Get a list of tasks: `task -V`
- Start your app such that it will restart on code changes: `~reStart`
- Clean all built files (if the incremental build needs to be reset): `clean`

## Running the Automated Tests in Local Sandbox

- (Optional) To include tests for IBM agbot ACLs: `export EXCHANGE_AGBOTAUTH=myibmagbot:abcdef`
- (Optional) To include tests for IBM IAM platform key authentication:
```
export EXCHANGE_IAM_KEY=myiamplatformkey
export EXCHANGE_IAM_EMAIL=myaccountemail@something.com
export EXCHANGE_IAM_ACCOUNT=myibmcloudaccountid
```
- Run the automated tests in a second shell (with the exchange server still running in the first): `sbt test`
- Run just 1 of the the automated test suites (with the exchange server still running): `sbt "testOnly **.AgbotsSuite"`
- Run the performance tests: `src/test/bash/scale/test.sh` or `src/test/bash/scale/wrapper.sh 8`
- Make sure to run `primedb.sh` before running the  `AgbotsSuite` test class to run all of the tests.

## Building and Running the Docker Container

- Update the version in `src/main/resources.version.txt`
- **This step doesn't work yet, use the 2 steps in the following bullet for now
  - To compile your local code, build the exchange container, and run it, run: `make`
- Or you can build and run in separate steps:
    - Compile your local code and build the exchange container: `make .docker-exec`
    - Run the container locally: `make start-docker-exec`
- Manually test container locally: `curl -sS -w %{http_code} http://localhost:8080/v1/admin/version`
    - Note: the container can not access a postgres db running locally on the host if the db is only listening for unix domain sockets or 127.0.0.1. See the **Preconditions** section above.
- Manually test the local container via https:
    - If you haven't already, set EXCHANGE_KEY_PW and run `make gen-key`
    - Add `edge-fab-exchange` as an alias for `localhost` in `/etc/hosts`
    - `src/test/bash/https.sh get services`
- Run the automated tests: `sbt test`
- Check the swagger info from the container: `http://localhost:8080/v1/swagger`
- Log output of the exchange svr can be seen via `docker logs -f exchange-api`, or might also go to `/var/log/syslog` depending on the docker and syslog configuration.
- At this point you probably want to `make clean` to stop your local docker container so it stops listening on your 8080 port, or you will be very confused when you go back to running new code in your sandbox, and your testing doesn't seem to be executing it.

### Notes About the Docker Image Build Process

- See https://www.codemunity.io/tutorials/dockerising-akka-http/
- Uses the sbt-native-packager plugin. See the above URL for what to add to your sbt-related files
- Build docker image: `sbt docker:publishLocal`
- Manually build and run the exchange executable
  - `make runexecutable`
- To see the dockerfile that gets created:
  - `sbt docker:stage`
  - `cat target/docker/stage/Dockerfile`

## Test the Exchange with Anax

- If you will be testing with anax on another machine, push just the version-tagged exchange image to docker hub, so it will be available to the other machines: `make docker-push-version-only`
- Just the first time: on an ubuntu machine, clone the anax repo and define this e2edev script:
```
mkdir -p ~/src/github.com/open-horizon && cd ~/src/github.com/open-horizon && git clone git@github.com:open-horizon/anax.git
# See: https://github.com/open-horizon/anax/blob/master/test/README.md
if [[ -z "$1" ]]; then
    echo "Usage: e2edev <exchange-version>"
    return
fi
set -e
sudo systemctl stop horizon.service   # this is only needed if you normally use this machine as a horizon edge node
cd ~/src/github.com/open-horizon/anax
git pull
make clean
make
make fss   # this might not be needed
cd test
make
make test TEST_VARS="NOLOOP=1 TEST_PATTERNS=sall" DOCKER_EXCH_TAG=$1
make stop
make test TEST_VARS="NOLOOP=1" DOCKER_EXCH_TAG=$1
echo 'Now run: cd $HOME/src/github.com/open-horizon/anax/test && make realclean && sudo systemctl start horizon.service && cd -'
set +e
```
- Now run the test (this will take about 10 minutes):
```
e2edev <exchange-version>
```

## Deploying the Container to Staging or Production

- Push container to the docker hub registry: `make docker-push-only`
- Deploy the new container to the staging or production docker host
    - Ensure that no changes are needed to the /etc/horizon/exchange/config.json file
- Sniff test the new container : `curl -sS -w %{http_code} https://<exchange-host>/v1/admin/version`

## Building the Container for a Branch

To build an exchange container with code that is targeted for a git branch:
- Create a development git branch A (that when tested you will merge to branch B). Where A and B can be any branch names.
- Locally test the branch A exchange via sbt
- When all tests pass, build the container: `rm -f .docker-compile && make .docker-exec-run TARGET_BRANCH=B`
- The above command will create a container tagged like: 1.2.3-B
- Test the exchange container
- When all tests pass, push it to docker hub: `make docker-push-only TARGET_BRANCH=B`
- The above command will push the container tagged like 1.2.3-B and latest-B
- Create a PR to merge your dev branch A to canonical branch B

## Exchange root User

### Putting Hashed Password in config.json

The exchange root user password is set in the config file (`/etc/horizon/exchange/config.json`). But the password doesn't need to be clear text. You can hash the password with:
```
curl -sS -X POST -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" -H "Content-Type: application/json" -d '{ "password": "PUT-PW-HERE" }' $HZN_EXCHANGE_URL/admin/hashpw | jq
```

And then put that hashed value in `/etc/horizon/exchange/config.json` in the `api.root.password` field.

### Disabling Root User

If you want to reduce the attack surface of the exchange, you can disable the exchange root user, because it is only needed under special circumstances. Before disabling root, we suggest you do:
- Create a local exchange user in the IBM org. (This can be used if you want to update the sample services, patterns, and policies at some point with `https://raw.githubusercontent.com/open-horizon/examples/master/tools/exchangePublishScript.sh`.):
  ```bash
  hzn exchange user create -u "root/root:PUT-ROOT-PW-HERE" -o IBM -A PUT-USER-HERE PUT-PW-HERE PUT-EMAIL-HERE
  ```

- Give 1 of the IBM Cloud users `admin` privilege:
  ```
  hzn exchange user setadmin -u "root/root:PUT-ROOT-PW-HERE" -o PUT-IBM-CLOUD-ORG-HERE PUT-USER-HERE true
  ```

Now you can disable root by setting `api.root.enabled` to `false` in `/etc/horizon/exchange/config.json`.

## Todos that may be done in future versions

- Granular (per org) service ACL support:
    - add Access type of BROWSE that will allow to see these fields of services:
      - label, description, public, documentation, url, version, arch
    - add acl table and resource with fields:
      - org
      - resource (e.g. service)
      - resourceList (for future use)
      - requester (org/username)
      - access
    - add GET, PUT, POST to manage these acls
    - add checks on GET service to use these acls
    - (later) consider adding a GET that returns all services of orgs of orgType=IBM
- Add rest method to delete a user's stale devices (carl requested)
- Add ability to change owner of node
- Add patch capability for node registered services
- Consider:
    - detect if pattern contains 2 services that depend on the same exclusive MS
    - detect if a pattern is updated with service that has userInput w/o default values, and give warning
    - Consider changing all creates to POST, and update (via put/patch) return codes to 200

## Changes in 2.10.0

- Fixed another case for issue 264
- Moved the sort of `/changes` data to exchange scala code (from the postgresql db), and simplified the query filters a little

## Changes in 2.9.0

- Issue 284: Notification Framework no longer throws an error for empty db responses

## Changes in 2.8.0

- Issue 278: Notification Framework V1.3 (bug fix of missing changes and increased efficiency)
- Issue 229: Pattern Search "service not in pattern" response fixed

## Changes in 2.7.2

- Changed the order of the akka route directives to match the path before the http method

## Changes in 2.7.1

- Fixed the logging of rejections
- Fixed listing all of a resource type from another org
- Added separate way to query icp/ocp exchange org

## Changes in 2.7.0

- Issue 277: Notification Framework Updates

## Changes in 2.6.0

- Fixed issue 262 - get icp cluster name once at the beginning
- Fixed issue 256 - trying to access a non-existent resource in another org incorrectly returned 403 instead of 404
- Fixed issue 264 - for auth exceptions, prefer returning retryable http codes
- Verified authentication using OCP
- Modified use of `ICP_EXTERNAL_MGMT_INGRESS` env var so it can optionally have `https://` prepended

## Changes in 2.5.0

- Made the IP and port the exchange listens on configurable in `config.json`
- Added graceful shutdown, and made wait time for in-flight requests configurable
- Enabled passing args to the exchange svr JVM by setting JAVA_OPTS

## Changes in 2.4.0

- Switch the db.run() steps to use the akka execution context
- Enabled setting akka config in the exchange `config.json` file

## Changes in 2.3.0

- Added back in Makefile `test` target for travis
- Updated local postgres config instructions in README.md
- Fixed issue 270: Wrong error response when org not prepended
- Fixed corner-case bug in `POST /orgs/{orgid}/services/{service}/dockauths`

## Changes in 2.2.0

- Issue 258: Notification Framework bugfixes
- Issue 265: POST /v1/orgs/{orgid}/search/nodes/error now filters on orgid 

## Changes in 2.1.0

- Added `heartbeatIntervals` field to org and node resources
- Fixed exception when route unrecognized

## Changes in 2.0.x

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

## Changes in 1.122.0

- Implement part 1 of issue 232: add exchange notification system : Resource Changes Route

## Changes in 1.121.0

- Fix issue 209: Change all occurrences of exchange checking db error msg content
- Fix issue 248: Pattern ID with trailing whitespace allowed

## Changes in 1.120.0

- Fix issue 213: hzn exchange node update (PATCH) wipes out registeredServices if input bad

## Changes in 1.119.0

- Implemented issue 239: Remove requirement of token in Node PUT for updates

## Changes in 1.118.0

- Implemented issue 202: Document using exchange root pw hash in config.json, and support disabling exchange root user
- Improved IAM API key and UI token error handling in IbmCloudModule:getUserInfo()
- Change /admin/hashpw so non-root users can run it

## Changes in 1.117.0

- Issue 231: Disable exchange org verification for ICP IAM authentication

## Changes in 1.116.0

- Issue 224: New route `POST /v1/orgs/{orgid}/search/nodes/service` as the previous service search route did not account for IBM services.
- Changes to scale driver for higher scale testing

## Changes in 1.115.0

- More scale driver streamlining
- Catch a db timeout exception that was surfacing as generic invalid creds

## Changes in 1.114.0

- Issue 924: Patterns cannot be made with an empty or nonexistent services field.
- Removed redundant `messages_en.txt` file as the default file is in English.

## Changes in 1.113.0

- Added fast hash to cached token/pw
- Invalidate id cache entry when authentication fails
- Re-implemented scale drivers in go
- Changed scale node.go and agbot.go logic to have agbot create node msgs each time it finds it in the search
- In the scale drivers added some more retryable errors, and made retry max and sleep configurable

## Changes in 1.112.0

- Issue 214: Add optional `arch` field to body of pattern search so the search filters only on the arch passed in

## Changes in 1.111.0

- New exchange search API to return how many nodes a particular service is currently running on
- Added pii translation files in `src/main/resources`

## Changes in 1.110.0

- Put the 2 cache mechanisms in the same branch/build, that can be chosen via the config file

## Changes in 1.109.0

- Implemented issue 187: Add API Route to search for nodes that have errors

## Changes in 1.108.0

- Fixed issue 171: Improve way exchange icp auth gets org to verify it (ICP 3.2.1 authentication of UI token (getting cluster name))

## Changes in 1.107.2 (built only in the auth-cache2 branch so far)

- Fixed auth exception being thrown for missing msg in msg file

## Changes in 1.107.1 (built only in the auth-cache2 branch so far)

- Implemented issue 187: Add API Route to search for nodes that have errors

## Changes in 1.107.0

- Implemented issue 204: made `nodes/{id}/errors` more flexible so anax can add whatever fields they want

## Changes in 1.106.0

- Fixed issue 207: Change use of icp-management-ingress DNS name for ICP 3.2.1 (also works in 3.2.0)
- Fixed issue 183: Exchange node not found for POST /msgs should return 404, not 500 (also fixed it for agbot msgs)
- Fixed issue 185: serviceVersions should never be empty
- Fixed issue anax 783: Allow setting the pattern for a node in the exchange (error handling)

## Changes in 1.105.0

- Msg file fixes
- Upgradedb updates
- Have scale scripts calculate the number of agreements each node HB

## Changes in 1.103.0

- Moved exchange messages into separate non-code files to enable translation 

## Changes in 1.102.0

- New exchange resource for node errors

## Changes in 1.101.0

- Optimize exchange pattern search for nodes to filter on node arch
- Incorporated sadiyah's analysis of exchange logs into the perf/scaling test drivers

## Changes in 1.100.0

- Moved business policy nodehealth defaults to config.json and made them longer
- Modified perf/scaling scripts to work with ICP

## Changes in 1.99.0

- Moved pattern nodehealth defaults to config.json and made them longer

## Changes in 1.98.0

- Fixed timing problem in public cloud api key authentication
- Added `scale/node.sh` and `scale/agbot.sh` performance/scale test drivers

## Changes in 1.97.0

- Added retries to IBM IAM API authenitcation calls
- Added additional api methods tested in `scale/test.sh`, and improved the error handling

## Changes in 1.96.0

- Switched to use internal ICP authentication endpoints
- Caught exception when no auth provided

## Changes in 1.95.0

- Creating a business policy no longer rejects an arch of `""`
- Updated scale tests

## Changes in 1.94.0

- Added verification of org during ICP IAM authentication

## Changes in 1.93.0

- Added check in pattern POST/PUT for service version not being set (anax issue 932)
- Removed user pw from debug level logging
- Added old tables to table drop list for /admin/dropdb, in case they were running with an old db
- Merged in fixes to exchange travis test (issue 88)

## Changes in 1.92.0

- Added updatedBy field to user resource in exchange

## Changes in 1.91.0

- Change requiredServices.version to requiredServices.versionRange

## Changes in 1.90.0

- Have business policy node search also filter on arch (issue 139)
- Check for update time of node agreement and node policy in exchange business policy search (issue 146)
- Ensure a customer can't set their own org to orgType IBM (only root can)

## Changes in 1.89.0

- Disallow public patterns in non-IBM orgs in the exchange
- Add date to exchange logging

## Changes in 1.88.0

- Added checking for valid service references in the userInput sections in patterns, business policies, and nodes
- Allowed admin user to change pw of other user in org, and allowed root user to change any pw

## Changes in 1.87.0

- Changed `userInput` field of patterns and business policies to include service info
- Added `userInput` field to nodes

## Changes in 1.86.0

- Fixed ExchConfigSuites so it works with default config.json for `make test` in travis

## Changes in 1.85.0

- Added use of ICP self-signed cert

## Changes in 1.84.0

- Added `userInput` section to pattern resource
- Added `userInput` section to business policy resource

## Changes in 1.83.0

- Add `arch` field to node

## Changes in 1.82.0

- Log all invalid credentials
- Removed resource object
- Added business policies to agbot
- Added node search api for business policies

## Changes in 1.81.0

- Fixed bug in initdb and dropdb for service and business policy tables

## Changes in 1.80.0

- Add ICP IAM auth via platform key for `hzn`

## Changes in 1.79.0

- Added iamtoken support for ICP
- Fixed cloud env vars in primedb.sh to be more general
- Fixed OneNodeProperty bug in NodeRoutes and NodesSuite

## Changes in 1.78.0

- Add Makefile and README support for building docker images for a branch
- Add business policy resource

## Changes in 1.77.0

- Fix swagger doc for node id
- Fixed bug: when org doesn't exist error msg is database-timeout
- Added running primedb.sh to README.md
- Add node and service policy resources

## Changes in 1.76.0

- Do not show keystore encoded password in log
- Do not return "invalid creds" when can't reach db

## Changes in 1.75.0

- Configure https/ssl at run time (instead of build time), so the same container can run with both http and https, or just http.
- Increase db auth access timeout to avoid timeouts under certain conditions.

## Changes in 1.74.0

- Add TLS/SSL support to jetty, instead of relying on the front end (e.g. haproxy) to terminate the SSL
- Increase default max number of resources by an order of magnitude

## Changes in 1.73.0

- Update scale test driver
- Support composite service url in `POST /orgs/{orgid}/patterns/{pattern}/search`
- Support composite service url in `/orgs/{orgid}/search/nodes`

## Changes in 1.72.0

- add field to org called orgType, which will have value IBM for any of our orgs that have ibm services in them
- allow any user to GET all orgs with filter of orgType=IBM
- delete support for the special `public` org
- fix bug in which in the access denied error msg it only reported the generic access needed

## Changes in 1.71.0

- POST /orgs/{orgid}/nodes/{id}/configstate to POST /orgs/{orgid}/nodes/{id}/services_configstate

## Changes in 1.70.0

- In node resource, make msgEndPoint and softwareVersions optional
- Add node post to be able to be able to update some of the configState attrs
- Remove Resource from swagger and Services

## Changes in 1.69.0

- Support authentication via ibm cloud iam token that comes from the web ui

## Changes in 1.68.0

- Fix support for multiple orgs associate with 1 ibm cloud account

## Changes in 1.67.0

- Make orgs/ibmcloud_id table column not need to be unique, to support multiple orgs for the same ibm cloud account
- Fix problem with spaces in creds (issue 90)

## Changes in 1.66.0

- Handled special case of getting your own user when using iamapikey

## Changes in 1.65.0

- Added better errors for ibm auth
- Added /admin/clearAuthCaches
- Added IBM Cloud auth automated tests

## Changes in 1.64.0

- Added IBM Cloud auth plugin

## Changes in 1.63.0

- Fixed swagger
- Cleaned up README.md
- Improved error in `POST/PUT/PATCH pattern` when reference service does not exist to output service org, url, arch, version range
- Added optional `documentation` field to service resource
- Added new `resource` definition that can be required by services to hold things like models
- Added `singleton` as a valid `sharable` value
- Added JAAS authentication framework

## Changes in 1.62.0

- Remove microservice, workload, and blockchain support (leaving just service support)
- Remove deprecated `PUT /orgs/{orgid}/agbots/{id}/patterns/{patid}` method (use POST instead)

## Changes in 1.61.0

- Support IAM API keys for bx cr access by adding `username` field to service dockauths (issue anax 651)

## Changes in 1.60.0

- Fixed creds in url parms when root creds are specified or when /orgs is queried (exchange-api issue 77)
- Fixed creds in url parms from 1 org querying a resource in another org (exchange-api issue 58)

## Changes in 1.59.0

- Add nodeOrgids to body of `/org/{orgid}/patterns/{pat-id}/nodehealth`

## Changes in 1.58.0

- Make some more fields in POST/PUT patterns and services optional.

## Changes in 1.57.0

- Support wildcard pattern in `POST /orgs/{orgid}/agbots/{id}/patterns` body

## Changes in 1.56.0

- `PUT /orgs/{orgid}/agbots/{id}/patterns/{patid}`: add nodeOrgid to the body
- `POST /orgs/{orgid}/patterns/{pattern}/search`: add a list of nodeOrgid to the body

## Changes in 1.55.0

- Automatically run /admin/initdb or /admin/upgradedb on startup

## Changes in 1.54.0

- Added `agreementLess` attribute to `services` section of pattern

## Changes in 1.53.0

- If POST /services/{svcid}/dockerauths is a dup, just update the current one
- Moved docker image to docker hub openhorizon

## Changes in 1.52.0

- Add services sub-resource called dockerauths for docker image auth tokens

## Changes in 1.51.0

- Fixed error upgrading db schema from earlier than db schema 3

## Changes in 1.50.0

- Fix build error in Schema.scala in wiotp enviroment

## Changes in 1.49.0

- allow IBM agbots to access private ms/wl/svc/pat via get /workloads etc.
- explicitly set http response code of 200 for successful GETs to avoid occasional 404's

## Changes in 1.48.0

- clean up variables in nodes suite tests
- add back in a few tests for workloads and microservices in pattern and node search
- pattern node search api now validates the serviceUrl
- change agbot agreement to service
- change put node status to service
- change name of service package field to imageStore
- test built image with compose db and verify exchange version output


## Changes in 1.47.0

### External changes

- change node search to service
- change node, node agreement, pattern search
- changed pattern to support service

### Limitations in This Version

- Agbot agreement resources still refer to workloadOrgid, workloadPattern, workloadUrl (but you can put service info in those fields, because they are not checked)
- Node status resources still refer to workloads and microservices (but i think you can put service info in those fields, because i don't think they are checked)


## Changes in 1.46.0

### External changes

- added keys to ms, wk, service, pattern (anax issue 498)
- Figured out how to set the Response codes in swagger
- Fixed the swagger 2 level resource problem


## Changes in 1.45.0

### External changes

- fixed some library versions to make the build work again
- added service resource (issue #51)
- detect if svc requires other svc with different arch


## Changes in 1.44.0

### External changes

- Allowed missing or empty torrent field in microservice and workload

## Changes in 1.43.0

### External changes

- Made swagger work again, with these limitations:
    - "Try it out" button still doesn't work
    - The longer "description" field of each method is not appearing
- Added dbSchemaVersion field to the output of /admin/status
- Upgraded:
    - scala from 2.12.1 to 2.12.4
    - scalatra from 2.5.1 to 2.6.2
    - from scalatra-sbt 0.5.1 to sbt-scalatra 1.0.1


## Changes in 1.42.0

### External changes

- Allowed node in other org to access agbot in IBM org


## Changes in 1.41.0

### External changes

- When creating/updating a workload and verifying the existence of the apiSpecs, it now treats the MS versions as ranges
- (No need to upgrade the db if coming from version 1.38 or later, altho it won't hurt either)


## Changes in 1.40.0

### External changes

- Added GET /admin/version
- Docker tag for exchange docker image no longer has the "v" before the version number
- For the references between resources that are not enforced by DB foreign keys, added checking when the resource is created, updated, or patched:
    - microservices referenced by a workload
    - workloads referenced by a pattern
    - patterns referenced by an agbot
    - pattern referenced by a node
- Made the following resource fields optional:
    - pattern: dataVerification can be omitted or specified as `{}`
    - microservice: downloadUrl can be omitted, matchHardware can be omitted or specified as `{}`
    - workload: downloadUrl can be omitted

(No need to upgrade the db if coming from version 1.38 or later, altho it won't hurt either)


## Changes in v1.39.0

### External changes

- Updated jetty version to 9.4.7 and fixed build to pull latest bug fixes in the 9.4 range
- Fixed non-pattern node search to not find pattern nodes
- Now filter both pattern and non-pattern node searches to not return nodes with empty publicKey values
- Added `"nodeHealth": { "missing_heartbeat_interval": 600, "check_agreement_status": 120 }` policy to patterns (it is a peer to the dataVerification field). Existing pattern resources in the DB will be converted on the way out. For new POST/PUTs this new field is optional in 1.40.
- Added POST /orgs/{orgid}/patterns/{patid}/nodehealth and POST /orgs/{orgid}/search/nodehealth for agbot to get node lastHeartbeat and agreement status

(No need to upgrade the db if coming from version 1.38, altho it won't hurt either)


## Changes in v1.38.0

### External changes

- Added PUT/GET/DELETE /orgs/{id}/agbots/{id}/patterns/{id} and removed the patterns field from the agbot resource. This allows each org/pattern pair of an agbot to be managed individually.
- If you are coming from exchange versions 1.35 - 37, you can run POST /admin/upgradedb (which preserves the data). If coming from an earlier version, you must drop all of the tables and run POST /admin/initdb.


## Changes in v1.37.0

### External changes

- Added PUT/GET/DELETE /orgs/{id}/nodes/{id}/status
- If you are coming from v1.35.0 or v1.36.0, you can run POST /admin/upgradedb (which preserves the data). If coming from an earlier version, you must drop all of the tables and run POST /admin/initdb.


## Changes in v1.36.0

### External changes

- Allow queries in another org at the /microservices, /workloads, /patterns, /bctypes/{id}/blockchains level (and only return the public resources)
- Added 'public' as a filter attribute for microservices, workloads, patterns.
- Removed old/unused sections api.specRef, api.objStoreTmpls, and api.microservices from config.json
- Replaced empty {} return from PUT nodes/{nodeid} with normal {"code": 201, "msg": "node <id> added or updated"}
- Supported config.json api.limits.* equal to 0 to mean unlimited


## Changes in v1.35.0

- Fixed bug in which users, agbots, and nodes couldn't read their own org

## Changes in v1.34.0

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


## Changes in v1.33.0

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Quick fix to not require a slash in the node pattern attribute if it is blank


## Changes in v1.32.0

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Added pattern field to node resource
- Move arch field 1 level up in pattern schema, and renamed it to workloadArch
- Implemented /org/{orgid}/patterns/{pat-id}/search


## Changes Between v1.30.0 and v1.31.0

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Removed the option on PUT users/{username} to set password or email to blank to mean don't update it. Implemented PATCH users/{username} instead.
- Changed config.json acls READ_MY_ORGS and WRITE_MY_ORGS to their singular versions
- Added 'admin' field to user, enabled admin users to do everything in their org (including create other users)
- Updated the pattern resource schema to more explicitly support multiple different workloads, and multiple versions of the same workload (see swagger)


## Changes Between v1.29.0 and v1.30.0

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Added deployment_overrides and deployment_overrides_signature to patterns.workloads
- Added check_rate (int) in the dataVerification


## Changes Between v1.28.0 and v1.29.0

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

## Changes Between v1.27.0 and v1.28.0

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

## Changes Between v1.26.0 and v1.27.0

- Added org resource (still need to implement ACLs for it and move other resources under it)
- Changed PUT /devices/{id}/agreements/{agid} to accept list of microservices (and corresponding GET and POST search changes)

## Changes Between v1.25.0 and v1.26.0

- Fixed bug in which the CORS header Access-Control-Allow-Origin was not set in the response

## Changes Between v1.24.0 and v1.25.0

- Added /microservices and /workloads resources
- Changed default permissions to allow devices and agbots to read all agbots
- Changed additional image tag from latest to volcanostaging

## Changes Between v1.23.0 and v1.24.0

- Converted to the newer build.sbt file and upgraded to sbt version 0.13.13
- Migrated to version 9.4.x of jetty
- Made ACLs read from config.json (instead of hardcoded)
- Fixed bug: with a brand new db, could not run POST /admin/initdb because root user was not defined.
- Switched the dockerfile FROM statement to the official jetty image
- Added msgs, bctypes, and blockchains to the scale test script
- Removed requirement for MS numAgreements be set to 1.

## Changes Between v1.22.0 and v1.23.0

- Added PUT/GET/DELETE /bctypes/{type} and PUT/GET/DELETE /bctypes/{type}/blockchains/{name} to store/retrieve BC info
- All DELETE methods not return 404 if the resource was not found. (Before it was inconsistent, some returned 204 and some 404.)
- Refactored authentication and authorization code, so now it is easier to read, easier to extend, and handles a few corner cases better. No external changes for all the mainline use cases.
- Changed the scala version used in building the container to be the latest stable and the same as my dev environment (2.12.1)
- Moved up to latest stable version of scalatra (2.4.1 ot 2.5.1)
- Access denied msgs now give details
- Fixed pw reset email to give link to the swagger entry to change your pw with the token

## Changes Between v1.21.0 and v1.22.0

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

## Changes Between v1.20.0 and v1.21.0

- adjusted debug logging to be more helpful and less verbose
- fixed POST admin/migratedb

## Changes Between v1.19.0 and v1.20.0

- added support for base64 encoded credentials in the header
- added optional filter params to GET /devices and /agbots
- now log who each rest api call is from
- root user is now saved in the persistent db (but still loaded/updated from config.json at startup)
- deleting a user will now delete all of its devices, agbots, and agreements (we now have a proper foreign key between users and devices/agbots, and we use the sql on delete cascade option, so now the db completely takes care of object referencial integrity)
