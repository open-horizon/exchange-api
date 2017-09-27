# Horizon Data Exchange Server and REST API

The data exchange API provides API services for the exchange web UI (future), the edge nodes, and agreement Bots.

The exchange service also provides a few key services for BH for areas in which the decentralized P2P tools
do not scale well enough yet. As soon as the decentralized tools are sufficient, they will replace these
services in the exchange.

## Preconditions

- [Install scala](http://www.scala-lang.org/download/install.html)
- (optional) Install conscript and giter8 if you want to get example code from scalatra.org
- Install postgresql locally (unless you have a remote instance you are using). Instructions for installing on Mac OS X:
    - `brew install postgresql`
    - `echo 'host all all <my-local-subnet> trust' >> /usr/local/var/postgres/pg_hba.conf`
    - `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = 'my-ip'/" /usr/local/var/postgres/postgresql.conf`
    - `brew services start postgresql`
    - test: `psql "host=<my-ip> dbname=<myuser> user=<myuser> password=''"`
- Add a config file on your development system at /etc/horizon/exchange/config.json with at least the following content (this is needed for the automated tests). Defaults and the full list of config variables are in `src/main/resources/config.json`:

```
{
	"api": {
		"db": {
			"jdbcUrl": "jdbc:postgresql://localhost/myuser",		// my local postgres db
			"user": "myuser",
			"password": ""
		},
		"smtp": {
			"host": "mysmtp.relay.com",	 // the SMTP relay svr the exchange uses to send pw reset emails
			"user": "myemail@email.net",    // email address
			"password": "myemailpw"    // email pw
		},
		"logging": {
			"level": "DEBUG"
		},
		"root": {
			"password": "myrootpw",
			"email": ""
		}
	}
}
```

## Building in Local Sandbox

- `./sbt`
- `jetty:start`
- Or to have the server restart automatically when code changes: `~;jetty:stop;jetty:start`
- Once the server starts, to try a simple rest method browse: [http://localhost:8080/v1/nodes?id=a&token=b](http://localhost:8080/v1/orgs/IBM/nodes?id=a&token=b)
- To see the swagger output, browse: [http://localhost:8080/api](http://localhost:8080/api)
- Run the automated tests (with the exchange server still running): `./sbt test`
- Run just 1 of the the automated test suites (with the exchange server still running): `./sbt "test-only exchangeapi.AgbotsSuite"`
- Run the performance tests: `src/test/bash/scale/test.sh` or `src/test/bash/scale/wrapper.sh 8`

## Building and Running the Container

- Update the `DOCKER_TAG` variable to the appropriate version in the Makefile
- To build the build container, compile your local code, build the exchange container, and run it: `make` . Or you can do the individual steps:
    - Build the build container: `make .docker-bld`
    - Build the code from your local exchange repo in the build container: `make docker-compile`
    - Build the exchange api container and run it locally: `make .docker-exec-run`
- Manually test container locally: `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic bp:mypw" http://localhost:8080/v1/orgs/IBM/nodes | jq .`
    - Note: the container can not access a postgres db running locally on the docker host if the db is only listening for unix domain sockets.
- Run the automated tests: `./sbt test`
- Export environment variable `DOCKER_REGISTRY` with a value of the hostname of the docker registry to push newly built containers to (for the make target in the next step)
- Push container to our docker registry: `make docker-push-only`
- Deploy the new container to a docker host
    - Ensure that no changes are needed to the /etc/horizon/exchange/config.json file
- Test the new container : `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic myuser:mypw" https://<exchange-host>/v1/orgs/IBM/nodes | jq .` (or may be https, depending on your deployment)
- To see the swagger info from the container: `https://<exchange-host>/api`
- Log output of the exchange svr can be seen via `docker logs -f exchange-api`, or it also goes to `/var/log/syslog` on the exchange docker host
- At this point you probably want to `make clean` to stop your local docker container so it stops listening on your 8080 port, or you will be very confused when you go back to running new code in your sandbox, and your testing doesn't seem to be executing it.

## Changes Between v1.33.0 and v1.34.0

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

### Todos left to be finished

- See if maxAgreements=0 is supported as unlimited (for both node registration, and for maxAgreements in config.json)
- support max object equal to 0 to mean unlimited
- Do consistency checking of patterns and workloads
- Remove empty return from PUT nodes/{nodeid} and microservices.disable option from config.json
- See if there is a way to fix the swagger hack for 2 level resources
- Consider changing all creates to POST
- Any other schema changes?


## Changes Between v1.32.0 and v1.33.0

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Quick fix to not require a slash in the node pattern attribute if it is blank


## Changes Between v1.31.0 and v1.32.0

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
