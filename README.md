# Horizon Data Exchange Server and REST API

The data exchange API provides API services for the exchange web UI (future), the edge devices, and agreement Bots.

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
- Once the server starts, to try a simple rest method browse: [http://localhost:8080/v1/devices?id=a&token=b](http://localhost:8080/v1/devices?id=a&token=b)
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
- Manually test container locally: `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic bp:mypw" http://localhost:8080/v1/devices | jq .`
    - Note: the container can not access a postgres db running locally on the docker host if the db is only listening for unix domain sockets.
- Run the automated tests: `./sbt test`
- Export environment variable `DOCKER_REGISTRY` with a value of the hostname of the docker registry to push newly built containers to (for the make target in the next step)
- Push container to our docker registry: `make docker-push-only`
- Deploy the new container to a docker host
    - Ensure that no changes are needed to the /etc/horizon/exchange/config.json file
- Test the new container : `curl -# -X GET -H "Accept: application/json" -H "Authorization:Basic myuser:mypw" https://<exchange-host>/v1/devices | jq .` (or may be https, depending on your deployment)
- To see the swagger info from the container: `https://<exchange-host>/api`
- Log output of the exchange svr can be seen via `docker logs -f exchange-api`, or it also goes to `/var/log/syslog` on the exchange docker host
- At this point you probably want to `make clean` to stop your local docker container so it stops listening on your 8080 port, or you will be very confused when you go back to running new code in your sandbox, and your testing doesn't seem to be executing it.

## Changes Between v1.28.0 and v1.29.0

### Todos left to be finished

- Rename devices to nodes
- Add schemaversion table and key upgradedb off of that
- Move bctype and blockchain under and add public field
- Modify tests to have each suite use its own org
- Modify PUT devices/{id}/agreements/{id} body to include the pattern and workload
- See if maxAgreements=0 is supported as unlimited
- Modify /search/devices for patterns
- Add 'admin' field to user, enable admin users to do everything in their org (including create other users)
- PUT orgs/{org}/devices/{device}/agreements/{agreementid} to be changed to take "microservices":[{"url":"ms url","org":"myorg"}] instead of an array of url strings like it is now.  Same change for agbots (url for workload and an org inside an object)
- Add list of orgs/patterns to agbot resource
- Implement the rest of the cross-org acls: identities in other orgs can read all public patterns/workloads/microservices/blockchains, IBM agbots can read all devices
- Do consistency checking of patterns and workloads
- See if there is a way to fix the swagger hack for 2 level resources
- Consider changing all creates to POST
- Any other schema changes?

### Limitations

- Need to dropdb and initdb, and re-enter data

### External Incompatible changes

- Users, devices, and agbots can now read their own org resource

### Internal things done in this version

- Test dropdb with older compose db
-

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

## Changes Between v1.18.0 and v1.19.0

- added fallback for getting device/agbot owner from the db during authentication
- automated tests can now use environment variables to run against a different exchange svr
- split the create part of PUT /users/{u} to POST /users/{u} so it can be rate limited. (Except PUT can still be used to creates users as root.)
- fixed but that didn't let POST /users/{u}/reset be called with no creds
- fixed schema of users table to have username be the primary key

## Changes Between v1.17.0 and v1.18.0

- Added support for putting token, but not id, in URL parms

## Changes Between v1.16.0 and v1.17.0

- Fixed bug when user creds are passed into url parms
- Increased timeouts when comparing auth cache to db, and temporarily fall back to cached

## Changes Between v1.15.0 and v1.16.0

- Added synchronization (locking) around the auth cache

## Changes Between v1.14.0 and v1.15.0

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

## Changes Between v1.13.0 and v1.14.0

- Changed field daysStale to secondsStale in POST /search/devices. **This is not a backward compatible change.**
- Caught json parsing exceptions so they don't directly get returned to the client.
- Finished db persistence for GET /devices and /devices/{id}

## Changes Between v1.12.0 and v1.13.0

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

## Changes Between v1.11.0 and v1.12.0

- Fixed poor error msg when root email in config.json is blank
- Added in-memory cache of non-hashed pw/tokens. Increased speed by more than 10x.
- (No external changes in this version AFAIK)

## Changes Between v1.10.0 and v1.11.0

- added scale/performance tests
- added a softwareVersions section of input to PUT /devices/{id} so devices can report their sw levels to the exchange. See swagger for details.
- POST /users/{username}/reset is now implemented and will email a timed pw reset token to the user's email address
- Made the logging level configurable via the config.json file


## Changes Between v1.9.0 and v1.10.0

- Now it hashes all of the passwords and tokens before storing them in the db
- Add 2 /admin routes that can only be run as root: reload (reload the config file) and hashpw (return a hash the given pw)
- Added support for the root pw in the config file to be a hashed value
- To run the automated tests you must now create a local config file with the root pw in it (see above)
- Straightened out some of the Makefile variables and dependencies

## Changes Between v1.8.0 and v1.9.0

- Added support for using an osgi version range in POST /search/devices
- Made GET / return html that lists available exchange services (so far just a link to the api swagger info)
- Fixed bug where PUT /devices/{id} created the device in the db even if the microservice url was wrong such that micro templates could not be returned
- Made the microservice url input to PUT /devices/{id} tolerant of a trailing /
- Added support for /etc/horizon/exchange/config.json. If that config file doesn't exist, the server will still run with default values appropriate for local development.
- Updated all of the bash test scripts to use a real microservice url.

## Changes Between v1.7.0 and v1.8.0

- PUT /devices/{id} now returns a hash with keys of specRef and values of microservice template json blob

## Changes Between v1.6.0 and v1.7.0

- Fixed bug: agbot couldn't run POST /search/devices
- Fixed a few other, more minor, access problems
- Updated swagger info to clarify who can run what
