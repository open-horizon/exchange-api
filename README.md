# Horizon Data Exchange Server and REST API

The data exchange API provides API services for the exchange web UI (future), the edge nodes, and agreement Bots.

The exchange service also provides a few key services for BH for areas in which the decentralized P2P tools
do not scale well enough yet. As soon as the decentralized tools are sufficient, they will replace these
services in the exchange.

## <a name="preconditions"></a>Preconditions for Local Development

- [Install scala](http://www.scala-lang.org/download/install.html)
- [Install sbt](https://www.scala-sbt.org/1.x/docs/Setup.html)
- (optional) Install conscript and giter8 if you want to get example code from scalatra.org
- Install postgresql locally (unless you have a remote instance you are using). Instructions for installing on Mac OS X:
    - Install: `brew install postgresql`
    - Note: when running/testing the exchange svr in a docker container, it can't reach your postgres instance on `localhost`, so configure it to also listen on your local IP:
      - set this to your IP: `export MY_IP=<my-ip>`
      - `echo "host all all $MY_IP/32 trust" >> /usr/local/var/postgres/pg_hba.conf`
      - `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = '$MY_IP'/" /usr/local/var/postgres/postgresql.conf`
      - `brew services start postgresql` or if it is already running `brew services restart postgresql`
    - Or if your test machine is on a private subnet:
      - trust all clients on your subnet: `echo 'host all all 192.168.1.0/24 trust' >> /usr/local/var/postgres/pg_hba.conf`
      - listen on all interfaces: `sed -i -e "s/#listen_addresses = 'localhost'/listen_addresses = '*'/" /usr/local/var/postgres/postgresql.conf`
      - `brew services start postgresql` or if it is already running `brew services restart postgresql`
    - Or you can run postgresql in a container and connect it to the docker network `exchange-api-network`
    - Test: `psql "host=$MY_IP dbname=postgres user=<myuser> password=''"`
- Add a configuration file on your development system at `/etc/horizon/exchange/config.json` with at minimum the following content (this is needed for the automated tests. Defaults and the full list of configuration variables are in `src/main/resources/config.json`):

  ```
  {
    "api": {
      "db": {
        "jdbcUrl": "jdbc:postgresql://localhost/postgres",    // my local postgres db
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
- When testing the exchange in an OpenShift Cluster the variables `EXCHANGE_IAM_ORG`, `EXCHANGE_IAM_KEY` and `EXCHANGE_MULT_ACCOUNT_ID` must be set accordingly.
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

## Code Coverage Report

  Code coverage is disabled in the project by default. The sbt command `sbt coverage` toggles scoverage checking on/off. To create a report of scoverage:
  
  - Execute `sbt coverage` to enable scoverage.
  - Run all tests see section above (`Running the Automated Tests in Local Sandbox`).
  - Create report running command `sbt coverageReport`.
  - Terminal will display where the report was written and provide a high-level percent summary.

## Linting

  Project uses Scapegoat. To use:
  
  - Run `sbt scapegoat`
  - Terminal will display where the report was written and provide a summary of found errors and warnings.

## Building and Running the Docker Container in Local Sandbox

- Update the version in `src/main/resources/version.txt`
- Add a second configuration file that is specific to running in the docker container:
  - `sudo mkdir -p /etc/horizon/exchange/docker`
  - `sudo cp /etc/horizon/exchange/config.json /etc/horizon/exchange/docker/config.json`
  - See [the Preconditions section](#preconditions) for the options for configuring postgresql to listen on an IP address that your exchange docker container will be able to reach. (Docker will not let it reach your host's `localhost` or `127.0.0.1` .)
  - Set the `jdbcUrl` field in this `config.json` to use that IP address, for example:
    - `"jdbcUrl": "jdbc:postgresql://192.168.1.9/postgres",`
- To compile your local code, build the exchange container, and run it locally, run:
  - `make .docker-exec-run-no-https`
  - If you need to rerun the container without changing any code:
    - `rm .docker-exec-run-no-https && make .docker-exec-run-no-https`
- Log output of the exchange svr can be seen via `docker logs -f exchange-api`, or might also go to `/var/log/syslog` depending on the docker and syslog configuration.
- Manually test container locally: `curl -sS -w %{http_code} http://localhost:8080/v1/admin/version`
- **Note:** The exchange-api does not support HTTPS until issue https://github.com/open-horizon/exchange-api/issues/259 is completed.
- Run the automated tests: `sbt test`
- **Note:** Swagger does not yet work in the local docker container.
- At this point you probably want to run `docker rm -f amd64_exchange-api` to stop your local docker container so it stops listening on your 8080 port. Otherwise you may be very confused when you go back to running the exchange via `sbt`, but it doesn't seem to be executing your tests.

### Notes About `config/exchange-api.tmpl`

- The `config/exchange-api.tmpl` is a application configuration template much like `/etc/horizon/exchange/config.json`. The template file itself is required for building a Docker image, but the content is not. It is recommend that the default content remain as-is when building a Docker image. 
- The content layout of the template exactly matches that of `/etc/horizon/exchange/config.json`, and the content of the config.json can be directly copied-and-pasted into the template. This will set the default Exchange configuration to the hard-coded specifications defined in the config.json when a Docker container is created.
- Alternatively, instead of using hard-coded values the template accepts substitution variables (default content of the `config/exchange-api.tmpl`). At container creation the utility `envsubst` will make a value substitution with any corresponding environmental variables passed into the running container by Docker, Kubernetes, OpenShift, or etc. For example:
    - `config/exchange-api.tmpl`:
        - "jdbcUrl": "$EXCHANGE_DB_URL"
    - Kubernetes config-map (environment variable passed to container at creation):
        - "$EXCHANGE_DB_URL=192.168.0.123"
    - Default `/etc/horizon/exchange/config.json` inside running container:
        - "jdbcUrl": "192.168.0.123"
- It is possible to mix-and-match hard-coded values and substitution values in the template.
- ***WARNING:*** `envsubst` will attempt to substitute any value containing a `$`, which will include the value of `api.root.password` if it is a hashed password. To prevent this either pass the environmental variable `ENVSUBST_CONFIG` with a garbage value, e.g. `ENVSUBST_CONFIG='$donotsubstituteanything'` (this will effectively disable `envsubst`), or pass it with a value containing the exact substitution variables `envsubst` is to substitute (`ENVSUBST_CONFIG='${EXCHANGE_DB_URL} ${EXCHANGE_DB_USER} ${EXCHANGE_DB_PW} ${EXCHANGE_ROOT_PW} ...'`), and of course you have to pass those environment variables values into the container.
    - By default `$ENVSUBST_CONFIG` is set to `$ENVSUBST_CONFIG=''` this causes `envsubst` to use its default opportunistic behavior and will attempt to make any/all substitutions where possible.
- It is also possible to directly pass a `/etc/horizon/exchange/config.json` to a container at creation using a bind/volume mount. This takes precedence over the content of the template `config/exchange-api.tmpl`. The directly passed config.json is still subject to the `envsubst` utility and the above warning still applies.

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

## Using TLS With The Exchange

- You need a PKCIS #12 cryptographic store (.pk12). https://en.wikipedia.org/wiki/PKCS_12
  - See Makefile targets `target/localhost.crt` (line 236), `/etc/horizon/exchange/localhost.p12` (line 243), and `truststore` (line 250) for a skeleton to use with OpenSSL.
  - OpenSSL is used for the creation of (1) self-signed certificate stating the application server is who it says it is, (2) the server's private key, and (3) the PKCS #12 which is just a portable secure store for everything.
  - The PKCS #12 is password protected. Set the environmental variable `EXCHANGE_TRUST_PW` when using the Makefile.
    - Set `export EXCHANGE_TRUST_PW=` when wishing to not have a password on the PKCS #12
- Set `api.tls.truststore` and `api.tls.password` in the Exchange's `/etc/horizon/echange/config.json` file.
  - `truststore` expects the absolute (full) path to your intended PCKS #12 as a string.
    - Setting this is the mechanism by which the Exchange knows to attempt to set up TLS in the application server.
    - Use `"truststore": null` to disable.
  - `password` expects the PKCS #12's password.
    - The Exchange will throw an error and self terminate on start if this password is not set or set `null`.
    - `api.tls.password` defaults to `null`.
    - When using a PKCS #12 that does not have a set password, set `api.tls.password` to `"password": "",` in the `/etc/horizon/exchange/config.json`.
  - See Makefile target `/etc/horizon/exchange/config-https.json` (line 201) for an idea.
- The default ports are `8080` for unencrypted traffic and `8083` for Encrypted.
  - These can be adjusted in the Exchange's `/etc/horizon/echange/config.json` file.
  - `api.service.portEncrypted` for changing the port listening for encrypted traffic.
  - `api.service.port` for changing the port listening for unencrypted traffic.
  - See Makefile target `/etc/horizon/exchange/config-https.json` (line 201) for an idea.
  - The Exchange is capable of hosting both HTTP and HTTPS traffic at the same time as well as mutually exclusively one. Freedom to mix-and-match.
    - HTTP and HTTPS are required to run on different ports. The Exchange always defaults to HTTP exclusively when in conflict.
    - If ports are manually undefined in the Exchange's `/etc/horizon/echange/config.json` file then HTTP on port `8080` is defaulted.
  - The Exchange does not support mixing HTTP and HTTPS traffic on either port.
- Only `TLSv1.3` and `TLSv1.2` HTTPS traffic is supported by the Exchange with TLS enabled.
  - `TLS_AES_256_GCM_SHA384` is the only supported TLSv1.3 cipher in the Exchange.
  - The `TLSv1.3` cipher `TLS_CHACHA20_POLY1305_SHA256` is available starting in Java 14.
  - The supported ciphers for `TLSv1.2` are `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` and `TLS_DHE_RSA_WITH_AES_256_GCM_SHA384`.
- [Optional] When using HTTPS with the Exchange the PostgreSQL database can also be configured with TLS turned on.
  - The Exchange does not require an SSL enabled PostgreSQL database to function with TLS enabled. 
  - See https://www.postgresql.org/docs/13/runtime-config-connection.html#RUNTIME-CONFIG-CONNECTION-SSL for more information.
  - Requires separate certificate (.cert) and private key (.key) files.
    - See Makefile target `/postgres.crt` (line 138) for an idea.
  - Requires flag `ssl=true` to be set.
  - Requires flag `ssl_min_protocol_version=TLSv1.3` to be set.
  - See makefile target `target/docker/.run-docker-db-postgres-https` for an idea and how to do this for a PostgreSQL Docker container.
- The Exchange does not support HTTP traffic redirects to HTTPS server-side. HTTPS traffic must be intended client-side.
- See the Makefile target chain `target/docker/.run-docker-icp-https` (line 272) for idea of a running Exchange and database in docker containers using TLS.
- Do to technical limitations the Swagger page will only refer to the Exchange's HTTPS traffic port.

## Configuration Parameters

`src/main/resources/config.json` is the default configuration file for the Exchange. This file is bundled in the Exchange jar. To run the exchange server with different values, copy this to `/etc/horizon/exchange/config.json`. In your version of the config file, you only have to set what you want to override.

### api.acls

| Parameter Name | Description       |
|----------------|-------------------|
| AdminUser      |                   |
| Agbot          |                   |
| Anonymous      | Not actually used |
| HubAdmin       |                   |
| Node           |                   |
| SuperUser      |                   |
| User           |                   |

### api.akka

Akka Actor: https://doc.akka.io/docs/akka/current/general/configuration-reference.html
</br>
Akka-Http: https://doc.akka.io/docs/akka-http/current/configuration.html

| Parameter Name                     | Description                             |
|------------------------------------|-----------------------------------------|
| akka.http.server.backlog           |                                         |
| akka.http.server.bind-timeout      |                                         |
| akka.http.server.idle-timeout      |                                         |
| akka.http.server.linger-timeout    |                                         |
| akka.http.server.max-connections   |                                         |
| akka.http.server.pipelining-limit  |                                         |
| akka.http.server.request-timeout   |                                         |
| akka.http.server.server-header     | Removes the Server header from response |

### api.cache

| Parameter Name         | Description                                                     |
|------------------------|-----------------------------------------------------------------|
| authDbTimeoutSeconds   | Timeout for db access for critical auth info when cache missing |
| IAMusersMaxSize        | The users that are backed by IAM users                          |
| IAMusersTtlSeconds     |                                                                 |
| idsMaxSize             | Includes: local exchange users, nodes, agbots (all together)    |
| idsTtlSeconds          |                                                                 |
| resourcesMaxSize       | Each of: users, agbots, services, patterns, policies            |
| resourcesTtlSeconds    |                                                                 |
| type                   | Currently guava is the only option                              |

### api.db

| Parameter Name               | Description                                                          |
|------------------------------|----------------------------------------------------------------------|
| acquireIncrement             |                                                                      |
| driverClass                  |                                                                      |
| idleConnectionTestPeriod     | In seconds; 0 disables                                               |
| initialPoolSize              |                                                                      |
| jdbcUrl                      | The back-end db the exchange uses                                    |
| maxConnectionAge             | In seconds; 0 is infinite                                            |
| maxIdleTime                  | In seconds; 0 is infinite                                            |
| maxIdleTimeExcessConnections | In seconds; 0 is infinite; culls connections down to the minPoolSize |
| maxPoolSize                  |                                                                      |
| maxStatementsPerConnection   | 0 disables; prepared statement caching per connection                |
| minPoolSize                  |                                                                      |
| numHelperThreads             |                                                                      |
| password                     |                                                                      |
| queueSize                    | -1 for unlimited, 0 to disable                                       |
| testConnectionOnCheckin      |                                                                      |
| upgradeTimeoutSeconds        |                                                                      |
| user                         |                                                                      |

#### api.defaults

- ##### api.defaults.businessPolicy
  | Parameter Name             | Description                                       |
  |----------------------------|---------------------------------------------------|
  | check_agreement_status     |                                                   |
  | missing_heartbeat_interval | Used if the service.nodeHealth section is omitted |
- ##### api.defaults.msgs
  | Parameter Name                | Description                                                            |
  |-------------------------------|------------------------------------------------------------------------|
  | expired_msgs_removal_interval | Number of seconds between deletions of expired node and agbot messages |
- ##### api.defaults.pattern
  | Parameter Name             | Description                                        |
  |----------------------------|----------------------------------------------------|
  | missing_heartbeat_interval | Used if the services.nodeHealth section is omitted |
  | check_agreement_status     |                                                    |

#### api.limits

| Parameter Name         | Description                                                                                                                 |
|------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| maxAgbots              | Maximum number of agbots 1 user is allowed to create, 0 for unlimited                                                       |
| maxAgreements          | Maximum number of agreements 1 node or agbot is allowed to create, 0 for unlimited                                          |
| maxBusinessPolicies    | Maximum number of business policies 1 user is allowed to create, 0 for unlimited                                            |
| maxManagementPolicies  | Maximum number of management policies 1 user is allowed to create, 0 for unlimited                                          |
| maxMessagesInMailbox   | Maximum number of msgs currently in 1 node or agbot mailbox (the sending side is handled by rate limiting), 0 for unlimited |
| maxNodes               | Maximum number of nodes 1 user is allowed to create, 0 for unlimited                                                        |
| maxPatterns            | Maximum number of patterns 1 user is allowed to create, 0 for unlimited                                                     |
| maxServices            | Maximum number of services 1 user is allowed to create, 0 for unlimited                                                     |

#### api.logging

| Parameter Name    | Description                                                                                 |
|-------------------|---------------------------------------------------------------------------------------------|
| level             | For possible values, see http://logback.qos.ch/apidocs/ch/qos/logback/classic/Level.html    |

#### api.resourceChanges

| Parameter Name     | Description                                                                                                                                                                                               |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| cleanupInterval    | Number of seconds between pruning the resourcechanges table in the db of expired changes - 3600 is 1 hour                                                                                                 |
| maxRecordsCap      | Maximum number of records the notification framework route will return                                                                                                                                    |
| ttl                | Number of seconds to keep the history records of resource changes (14400 is 4 hours). When agents miss 1 or more heartbeats, they reset querying the /changes route, so they do not need very old entries |

#### api.root

| Parameter Name   | Description                                            |
|------------------|--------------------------------------------------------|
| enabled          | If set to false it will not honor the root credentials |
| password         | Set this in your own version of this config file       |

#### api.service

| Parameter Name                      | Description                                                                    |
|-------------------------------------|--------------------------------------------------------------------------------|
| host                                |                                                                                |
| port                                | Services HTTP traffic                                                          |
| portEncrypted                       | Services HTTPS traffic                                                         |
| shutdownWaitForRequestsToComplete   | Number of seconds to let in-flight requests complete before exiting the server |

#### api.tls

| Parameter Name | Description                                                                                                |
|----------------|------------------------------------------------------------------------------------------------------------|
| password       | Truststore's password                                                                                      |
| truststore     | Absolute path and name of your pkcs12 (.p12) truststore that contains your tls certificate and private key |

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

## Changes in 2.103.0
- Issue 618: updated Swagger docs for `organization` routes
- Issue 619: fixed date parsing bug in `POST /orgs/{orgid}/changes`
- Issue 620: fixed heartbeat bug in `POST /orgs/{orgid}/changes`
- Issue 621: fixed authorization bug in `POST /orgs/{orgid}/agreements/confirm`

## Changes in 2.102.0
- Update the Exchange to OpenJDK17 from 11
- Update the Exchange to UBI 9 minimal from 8
- Add TLS 3.0 algorithm `TLS_CHACHA20_POLY1305_SHA256` to the approved algorithms list for TLS connections.
- Updated Sbt to version 1.6.2 from 1.6.1
- Changed GitHub action to use OpenJDK 17 instead of AdoptJDK 11

## Changes in 2.101.4
- Internationalization updates.

## Changes in 2.101.3
- Internationalization updates.

## Changes in 2.101.2
- Internationalization updates.

## Changes in 2.101.1
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

## Changes in 2.101.0
- Issue 581: Array order for versions maintained in the Exchange's DB and returned correctly when retrieved using a GET.
- Issue 582: Nodes, Agbots, Users, and Admin can now read resource changes for AgentFileVersions.

## Changes in 2.100.0
- Issue 572: All request body values are optional except for `scheduledTime`.
- Updated internationalization.

## Changes in 2.99.0
- Issue 574: Any Agbot can perform destructive requests. All role types can read.

## Changes in 2.98.1
- Issue 541: Renamed the directory `\doc` to `\docs`.
- Issue 576: Removed duplicated message key.

## Changes in 2.98.0
- Issue 571: Response Body Changes For NMP Status GET routes

## Changes in 2.97.0
- Issue 558: Added AgentFileVersion APIs

## Changes in 2.96.0
- Issue 566: Fixes GET route schema for NMP Status

## Changes in 2.95.0
- Issue 557: Added Node Management Policy Status APIs

## Changes in 2.94.0
- Issue 556: Updated the DB/Http schemas for Node Management Policies and routes.
- Simplified Table Query syntax in the application's source.

## Changes in 2.93.0
- Issue 560: Organization Administrators can now read all nodes organization wide from `orgs/{orgid}/search/nodes/service`

## Changes in 2.92.0
- Issue 555: Nodes are now able to read change transactions for Node Management Policies.
- Updated Sbt to version `1.6.1`.

## Changes in 2.91.0
- Issue 553: Users cannot create/modify/delete node management policies.
- Locked `akka-http-jackson` to version `1.37.0`.

## Changes in 2.90.4
- Issue 549: Updated Sbt to version `1.6.0`.
- Locked Slick to version `3.3.3`.
- Locked Slick-PG to version `0.19.3`.

## Changes in 2.90.3
- Issue 549: Updated Sbt to version `1.5.7`.

## Changes in 2.90.2
- Issue 547: Updated Sbt to version `1.5.6`.

## Changes in 2.90.1
- Issue 544: error for node policy API when deployment or management attribute is empty

## Changes in 2.90.0
- Issue 538: New format for node policy APIs

## Changes in 2.89.0
- Issue 537: Add node management policy

## Changes in 2.88.0
- No changes, version bump for release.

## Changes in 2.87.0
- Translation updates

## Changes in 2.86.0
- Lock the dependency `com.github.swagger-akka-http.swagger-akka-http` to version `2.4.2` to prevent pulling in Akka HTTP version `10.2.6` modules.

## Changes in 2.85.0
- Translation updates

## Changes in 2.84.0
- Removed translations in log statements

## Changes in 2.83.0
- Token validation requirements removed, pending redesign
- Translation updates

## Changes in 2.82.0
- Token validation requirements added

## Changes in 2.81.0
- Issue 518 Fix: Updated functionality of `POST /services_configstate` no longer updates version, only filters by it
- New translation files

## Changes in 2.80.0
- Issue 494: Lower rounds for password hash for node tokens
- Issue 518: Add version to the node registeredServices

## Changes in 2.79.0
- Issue 517: Fix cases where message intervals are not pulled from the configuration file until after timers are set.

## Changes in 2.78.0
- Issue 515: Database record trimming is now working again.

## Changes in 2.77.0
- Removed line comments from Swagger request/response body examples. Preventing proper rendering of the JSON examples.
- Disabled response header Content-Type.

## Changes in 2.76.0
- Added test cases in PatternSuite and BusinessSuite to ensure secrets are added as expected.  

## Changes in 2.75.0
- Enabled support for TLSv1.2. TLSv1.2 is in support of OpenShift 4.6. The 4.6 HAPoxy router is built on top of RHEL7 which does not support TLSv1.3.

## Changes in 2.74.0
- Readme Update: Added section on using TLS with the Exchange.

## Changes in 2.73.0
- Issue 491: Updated the service definition, deployment policy and pattern definitions to support vault based secrets to be used with Open Horizon.
     
## Changes in 2.72.0
- Issue 259: Added TLS support to the Exchange.
- Updated Akka: 2.6.10 -> 2.6.14.
- Updated Akka-Http: 10.2.2 -> 10.2.4.
- Updated Swagger UI to version 3.47.1.
- Updated Exchange's Swagger. APIs now sort by Tag -> API -> Method.
- Remade Makefile.

## Changes in 2.71.0
- Issue 493: Added route GET/orgs/{orgid}/status to fetch org specific information.

## Changed in 2.70.0
- Fixed issue 423: Upgrade Exchange To Use Akka v2.6.x.
- Also updated sbt, scala, and project dependencies.

## Changes in 2.69.0
- Added mulitple response headers to all Http responses.
  - `Cache-Control: max-age=0, must-revalidate, no-cache, no-store`
  - `Content-Type: application/json; charset=UTF-8`
  - `X-Content-Type-Options: nosniff`
- Removed `Server` response header from all Http responses.
- Added `application/json` mediatype to all http 200 and 201 responses in the OpenAPI 3.0 Swagger documentation.

## Changes in 2.68.0
- No changes, version bump.

## Changes in 2.67.0
- `ApiTime` string methods are wrapped with `fixFormatting()` to remove the issue where seconds or milliseconds get removed

## Changes in 2.66.0
- HubAdmins now have permission to `READ_ALL_AGBOTS` and `WRITE_ALL_AGBOTS` to facilitate org creation through hzn cli
- Users can no longer be a HubAdmin and an OrgAdmin at the same time
- New translation files
- travis.yml updates 

## Changes in 2.65.0
- Updating `lastUpdated` field of node on `POST /services_configstate` route
- New translation files, and file name fixes
- travis.yml updates

## Changes in 2.64.0
- Patch added to fix for issue 448. Missed one log message.

## Changes in 2.63.0
- Fixed issue 418: POST ​/v1​/orgs​/{orgid}​/agbots​/{id}​/agreements​/confirm wrong in swagger
- Added new translation files
- Fixed issue 448: Remove node token from log messages
- Fixed error message id `user.cannot.be.in.root` not found
- Fixed issue 462: Altering SQL to avoid inequalities for checking NF for /changes route for nodes reduces ExchangeDB CPU utilization
- Some progress on issue 451 (the policy /search api)
  - Analyzed the DB query code, adding many comments along the way. Did not find any problems

## Changes in 2.62.0
- Fixed issue 464: NPE in Exchange on PATCH business policies with incorrect payload returns incorrect HTTP status code - doesn't tell user what is wrong
- Fixed issue 176: When user or org is deleted, delete all corresponding auth cache entries
- Fixed issue 440: Add max parameter on GET /msgs calls

## Changes in 2.61.0

- Fixed issue 449: check for nodes in auth cache/table first
- Fixed issue 438: Exchange Auth Cache more granular TTL configuration
- Issue 454: Tested and confirmed current behavior is correct when a user and node have the same id
- Fixed issue 436: Hub Admin Bug Fixes/Improvements

## Changes in 2.60.0

- Issue 456: Change config value `akka.http.server.request-timeout` to `45s`
- Issue 455: Avoid DB reads if `maxMessagesInMailbox` and `maxAgreements` are `0` which is default and means unlimited
- Issue 458: Lower default values of resourceChanges ttl and cleanupInterval

## Changes in 2.59.0

- Issue 429: add `noheartbeat=true` option to APIs `PUT /orgs/{orgid}/nodes/{nodeid}` and `PUT /orgs/{orgid}/nodes/{nodeid}/agreements/<agreement-id>`
- Issue 419: add `noheartbeat=true` option to `PUT /orgs/{orgid}/nodes/{nodeid}/policy`

## Changes in 2.58.0

- Issue 445: Add the configState field to the node status API
- Remove `bluehorizon` from the swagger info. (We've decommissioned that site.)

## Changes in 2.57.0

- Issue 435: Add label and description fields to node and service policy objects

## Changes in 2.56.0

- Issue 425 - Upgraded the Exchange to Scala 2.13.3

## Changes in 2.55.0

- Issue 267 - Upgraded the Exchange to Java 11 and SBT 1.4.0. Upgraded Dockerfile to OpenJDK 11. Upgraded Travis CI specification to OpenJDK 11 and Ubuntu 20.04.

## Changes in 2.54.0

- Issue 427 - Reverting some thread pool changes to version 2.51.0

## Changes in 2.53.0

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

## Changes in 2.52.0

- Issue 421 - Added two OpenAPI 3.0 specification artifacts to `/doc/` project directory. One is for general users and the other is for developers.
- The Exchange's API version number is now properly specified in the default `swagger.json` file the Exchange generates.
- Corrected two test case errors in the `TestBusPolPostSearchRoute` test suite.

## Changes in 2.51.0

- Issue 387: Removed delay in cleanup of node and agbot msgs. Changed default interval to 30 minutes.

## Changes in 2.50.0

- Translation Update

## Changes in 2.49.0

- Issue 413: Table drop order has been reordered and all queries now cascade

## Changes in 2.48.0
- Issue 410: Changed org maxNodes limit warning message
- Issues 408: Removed duplicates from messages.txt

## Changes in 2.47.0
- Issue 406: `getUserAccounts` function in IbmCloudModule
- Issues 379 and 369: Authentication pathway for Multitenancy with verifying the org based on the associated account ID

## Changes in 2.46.0
- Issue 395: `POST /myorgs` route added
- Updated translations

## Changes in 2.45.0
- Issue 400: Exchange logs org deletions in `resourcechanges` table. Includes dropping orgid foreign key in `resourcechanges` table. 
- Issue 400: Exchange always reports org creations to agbots

## Changes in 2.44.0

- Issue 396: Added routes "GET .../agbots/{agboid}/msgs/{msgid}", and "GET .../nodes/{nodeid}/msgs/{msgid}".

## Changes in 2.43.0
- Issue 370: Updated messages.txt

## Changes in 2.42.0

- Added Hub Admin Role and Permissions
- Issue 395: `GET /v1/admin/orgstatus` route added for UI Hub Admin Dashboard
- Limits field added to org resource
- Issue 388: Fixed permissions settings of `/search/nodes/service`
- Issue 392: Fixed issues with org PATCH route

## Changes in 2.41.0

- Issue 383: Extended route to return node policy details as well.

## Changes in 2.40.0

- Issue 310 - Post policy search now supports pagination of results. The route is more efficient about dividing work between multiple Agbots.
  - Refactored post pattern search, no pagination added at this time.
  - Refactored policy search test cases. Policy search now has its own test suite.
  - A table for handling pagination offsets an Agbot sessions specific to policy search has been added to the database.

## Changes in 2.39.0

- Issue 383: Implemented "GET /v1/orgs/{orgid}/node-details" route.

## Changes in 2.38.0

- Fixed Issue 380: Delete of agbotmsgs and agent msgs by TTL can cause deadlocks with an akka actor running the deletions in a configurable interval

## Changes in 2.37.0

- Added the environmental variable `$ENVSUBST_CONFIG` to the Dockerfile for controlling the behavior of the utility `envsubst`.
- Changed the default UBI metadata labels for `release` to be the same as `version`, and `vendor` to `Open Horizon`.
- Moved around and rewrote README documentation for `config/exchange-api.tmpl` giving it its own subsection under `Building and Running the Docker Container`.

## Changes in 2.36.0

- Issue 376: Avoid writing to resourcechanges when msgs are deleted

## Changes in 2.35.0

- Issue 373: Removed not null constraint on lastheartbeat column in nodes table
- Updated `io.swagger.core.v3` versions specified to remove Jackson Databind incompatibility

## Changes in 2.34.0

- Issue 365: The PUT /orgs/{orgid}/nodes/{nodeId} route will no longer set/update a node's last heartbeat.

## Changes in 2.33.0

- Issue 313: Expanded unit testing structure to cover some of the Exchange.
- Added code coverage tool scoverage to the project.
- Added linter tool Scapegoat to the project.
- Open Horizon domain prefix added to system testing package.
- sbt will now pull-down a newer version of plugins if one is available. Defined versions are now the minimum accepted.

## Changes in 2.32.0

- Validated and updated as needed all request bodies and response bodies on the swagger page.
- Some additional minor swagger bugfixes.
- `swagger-scala-module` back to version `latest.release`

## Changes in 2.31.0

- Removed "Try it out" buttons from Swagger UI.
- Replaced Swagger parsed examples with custom examples where provided.
- Updated Swagger UI to version 3.26.0.
- Corrected occurrences where Swagger UI parameter names differed from REST API parameter names.
- Added API groupings to Swagger.
- Alpha-numerically sorted API groups in swagger.
- Alpha-numerically sorted REST API in Swagger.
- Swagger groupings show collapsed by default.

## Changes in 2.30.0

- Updated translations

## Changes in 2.29.0

- Issue 342: Notification Framework Performance: Added agbot filters for nodemsgs, agbotmsgs, nodestatus, nodeagreements, and agbotagreements

## Changes in 2.28.0

- Issue 358: Limited user role on `POST /org/{orgid}/search/nodes/error` API to only retrieve nodes self owned.

## Changes in 2.27.0

- Issue 251: Added `GET /<orgid>/search/nodes/error/all`
- Updated translation files
- Catching duplicate key error on `POST /orgs/{orgid}/users/{username}`

## Changes in 2.26.0

- Issue 314: Added `GET /catalog/<orgid>/patterns` and `GET /catalog/<orgid>/services`

## Changes in 2.25.0

- Fixed Issue 330: Postgres error handling

## Changes in 2.24.0

- Issue 350: Removed `read_all_nodes` from default permissions for `user` role, and removed `read my nodes` from default permissions for `node` role.
- Issue 352: Removed test container. Testing is now done locally to the source code.

## Changes in 2.23.0

- Issue 346: `connectivity` field in PUT `/nodes/<id>/status` now optional
- Issue 345: Node `lastUpdated` field now updated on node policy and node agreement deletions
- Issue 307: Changed policy property type from `list of string` to `list of strings`

## Changes in 2.22.0

- Additional Docker labels have been added to the amd64_exchange-api image in compliance with Red Hat certification.
- An Apache version 2.0 license has been added to the amd64_exchange-api image in compliance with Red Hat certification.
- The ability to specify the Exchange API's configuration at container creation has been added.

## Changes in 2.21.0

- Exchange API now uses Red Hat's Universal Base Image (UBI) 8 Minimal instead of Debian.
- SBT Native Packager updated to version 1.7.0 from 1.5.1.

## Changes in 2.20.0

- Issue 321: Updates to NodeStatus resource to support edge clusters

## Changes in 2.19.0

- Issue 320: Expand test suite for Admin routes to include API calls made by non-administer roles.
- Added SBT plugin for Eclipse.

## Changes in 2.18.0

- Issue 333: AgbotMsgs should not be deleted when nodes are deleted, removed node foreign key in `AgbotMsgs`

## Changes in 2.17.0

- Issue 295: Notification Framework performance updates
- Issue 324: Notification Framework ChangeId now of type bigint

## Changes in 2.16.0

- Issue 269: Notification Framework now handles changes in org resource
- Fixed issue 303: Notification Framework Agbot case wasn't automatically checking agbot's org

## Changes in 2.15.1

- Fixed issue 294: remove no longer used `src/main/webapp`

## Changes in 2.15.0

- Fixed issue 301: listing all business policies in another org returns 404 instead of 403
- Added field `nodeType` to node resource
- Added fields `clusterDeployment`, and `clusterDeploymentSignature` to service resource
- Return `nodeType` instead of `msgEndPoint` in the pattern and business `/search` routes

## Changes in 2.14.0

- Issue 311: Notification Framework Agbot Case
- Fixes for Scalatest upgrade to 3.1

## Changes in 2.13.0

- Issue 312: Using only node table's lastUpdated field to filter on (updating lastUpdated in node, policy, and agreement changes)

## Changes in 2.12.3

- Fix for `ZonedDateTime.now` truncating seconds and/or milliseconds when they are zero

## Changes in 2.12.2

- Fixed issue 296: invalid OCP API key was returning 502 instead of the correct 401

## Changes in 2.12.1

- Temporarily removed the trimming of the `resourcechanges` table

## Changes in 2.12.0

- Notification Framework: added indices on columns, added sort and limit back to query, added hitMaxRecords boolean field to response

## Changes in 2.11.1

- Notification Framework: When the db returns an empty response give back the largest changeId from the table

## Changes in 2.11.0

- Added configurable trimming of the resourcechanges table
- Removed `lastUpdated` filter for most common resourcechanges table query cases
- Added custom akka exception handler to return 502 (instead of 500) for db access errors in the routes
- Added `GET /changes/maxchangeid` route to more efficiently get max changeid during agent initialization

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
- Incorporated Sadiyah's analysis of exchange logs into the perf/scaling test drivers

## Changes in 1.100.0

- Moved business policy nodehealth defaults to config.json and made them longer
- Modified perf/scaling scripts to work with ICP

## Changes in 1.99.0

- Moved pattern nodehealth defaults to config.json and made them longer

## Changes in 1.98.0

- Fixed timing problem in public cloud api key authentication
- Added `scale/node.sh` and `scale/agbot.sh` performance/scale test drivers

## Changes in 1.97.0

- Added retries to IBM IAM API authentication calls
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
