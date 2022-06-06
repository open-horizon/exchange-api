# Make targets for the Exchange REST API server

SHELL = /bin/bash -e

# Some of these vars are also used by the Dockerfiles
ARCH ?= amd64
COMPILE_CLEAN ?= clean
DOCKER_NAME ?= amd64_exchange-api
DOCKER_NETWORK ?= exchange-api-network
DOCKER_REGISTRY ?= openhorizon
VERSION ?= $(shell cat src/main/resources/version.txt)
#todo: get this value from the target git branch
TARGET_BRANCH ?=
ifneq ($(TARGET_BRANCH),)
  BRANCH = -$(TARGET_BRANCH)
else
  BRANCH =
endif
DOCKER_TAG ?= $(VERSION)$(BRANCH)
DOCKER_LATEST ?= latest$(BRANCH)
DOCKER_OPTS ?= --no-cache
IMAGE_STRING = $(DOCKER_REGISTRY)/$(ARCH)_exchange-api
EXCHANGE_API_DIR ?= /src/github.com/open-horizon/exchange-api
# This version corresponds to the Version variable in project/build.scala
# EXCHANGE_API_WAR_VERSION ?= 0.1.0
# Location of config.json and icp/ca.crt in the container
EXCHANGE_CONFIG_DIR ?= /etc/horizon/exchange#$(PROJECT_DIRECTORY)/target/etc/horizon/exchange
EXCHANGE_CONTAINER_CONFIG_DIR ?= /etc/horizon/exchange
EXCHANGE_CONTAINER_PORT_HTTP ?= 8080
EXCHANGE_CONTAINER_PORT_HTTPS ?= 8083
# Note: this home dir in the container must match what is set for daemonUser in build.sbt
EXCHANGE_CONTAINER_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_POSTGRES_CERT_FILE)
EXCHANGE_CONTAINER_TRUST_DIR ?= /etc/horizon/exchange
EXCHANGE_FE_HEADER ?= issuer
EXCHANGE_HOST_ALIAS ?= edge-fab-exchange

ifeq ($(shell uname),Darwin)
  # Mac OS X
  EXCHANGE_HOST_CONFIG_DIR ?= /private$(EXCHANGE_CONFIG_DIR)
  EXCHANGE_HOST_ICP_CERT_FILE ?= /private$(EXCHANGE_ICP_CERT_FILE)
else
  # Assume Linux (could test by test if OS is Linux)
  EXCHANGE_HOST_CONFIG_DIR ?= $(EXCHANGE_CONFIG_DIR)
  EXCHANGE_HOST_ICP_CERT_FILE ?= $(EXCHANGE_ICP_CERT_FILE)
endif

# HTTP and HTTPS must operate on different ports.
# Exchange defaults to HTTPS when the same port number is given for both protocols.
# Use 'null' to disable associated protocol.
EXCHANGE_HOST_PORT_HTTP ?= 8080
EXCHANGE_HOST_PORT_HTTPS ?= 8083
# The public cert we should use to connect to an ibm cloud postgres db
EXCHANGE_HOST_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_CONFIG_DIR)/postres-cert/root.crt
# Directory location of the SSL truststore that is used to serve Https traffic. This need to be fully qualified, so docker can mount it into the container for the Exchange to access.
EXCHANGE_HOST_TRUST_DIR ?= $(PROJECT_DIRECTORY)/target/etc/horizon/exchange/truststore
EXCHANGE_ICP_CERT_FILE ?= /etc/horizon/exchange/icp/ca.crt
# Set to "DEBUG" to turn on debugging
EXCHANGE_LOG_LEVEL ?= DEBUG#INFO
# Number of days the SSL certificate is valid for
EXCHANGE_TRUST_DUR ?= 1
EXCHANGE_TRUST_PW ?=
# Use this to pass args to the exchange svr JVM by overriding JAVA_OPTS in your environment
JAVA_OPTS ?=#-Xmx1G
POSTGRES_CONTAINER_ADDRESS ?= $(shell docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(POSTGRES_CONTAINER_NAME))
POSTGRES_CONTAINER_NAME ?= postgres
POSTGRES_DB_NAME ?= exchange
POSTGRES_DB_PORT ?= 5432
POSTGRES_DB_USER ?= admin
PROJECT_DIRECTORY ?= $(shell pwd)
# Try to sync this version with the version of scala you have installed on your dev machine, and with what is specified in build.sbt
SCALA_VERSION ?= 2.13.8
SCALA_VERSION_SHORT ?= 2.13


# Altho the name is a little misleading, "docker-run-icp" just means build the container used for running the exchange
.PHONY: default
default: run-docker-icp

.PHONY: all
all: cleanest run-docker-icp-https test


# Utility ---------------------------------------------------------------------
.PHONY: testmake
testmake:
	@echo "BRANCH=$(BRANCH)"
	@echo "DOCKER_TAG=$(DOCKER_TAG)"
	@echo "DOCKER_LATEST=$(DOCKER_LATEST)"

.PHONY: version
version:
	@echo $(VERSION)


# Pre-Compile -----------------------------------------------------------------
# Get the latest version of the swagger ui from github and copy the dist dir into our repo in src/main/resources/swagger. That dir
# is loaded by SwaggerDocService.scala:SwaggerUiService
.PHONY: sync-swagger-ui
sync-swagger-ui:
	rm -fr ./target/swagger/swagger-ui.backup
	mkdir -p ./target/swagger/swagger-ui.backup
	cp -a src/main/resources/swagger/* ./target/swagger/swagger-ui.backup    # backup the version of swagger-ui we are currently using, in case the newer verion does not work
	git -C ~/git/swagger-api/swagger-ui pull    # update the repo
	cp -a ~/git/swagger-api/swagger-ui/dist/ src/main/resources/swagger      # copy the latest dist dir from the repo into our repo
	sed -e 's|https://petstore.swagger.io/v2/swagger.json|/v1/api-docs/swagger.json|' src/main/resources/swagger/index.html > ./target/swagger/swagger-index.html
	mv ./target/swagger/swagger-index.html src/main/resources/swagger/index.html


# Package ---------------------------------------------------------------------
## Package - Docker -------------------
target/docker/stage/Dockerfile:
	sbt docker:publishLocal

.PHONY: package-dockerfile
package-dockerfile: target/docker/stage/Dockerfile

## Package - Jar ----------------------
target/scala-$(SCALA_VERSION_SHORT)/amd64_exchange-api_$(SCALA_VERSION_SHORT)-$(VERSION).jar: $(wildcard *.scala) $(wildcard *.java)
	sbt stage

.PHONY: package-jar
package-jar: target/scala-$(SCALA_VERSION_SHORT)/amd64_exchange-api_$(SCALA_VERSION_SHORT)-$(VERSION).jar


# Pre-Run  --------------------------------------------------------------------
## Pre-run - Docker Network -----------
target/docker/.docker-network: target/docker/stage/Dockerfile
	docker network create $(DOCKER_NETWORK)
	@touch $@

.PHONY: docker-network
docker-network: target/docker/.docker-network

## Pre-run - Postgres -----------------
target/postgres:
	mkdir -p target/postgres

## Creates a self-signed TLS certificate and private key for localhost
/postgres.crt:
	openssl req -x509 -days $(EXCHANGE_TRUST_DUR) -out target/postgres.crt -keyout target/postgres.key \
    -newkey rsa:4096 -nodes -sha512 \
    -subj '/CN=localhost' -extensions EXT -config <( \
    printf "[dn]\nCN=localhost\n[req]\ndistinguished_name = dn\n[EXT]\nsubjectAltName=DNS:localhost\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth")
	sudo cp target/postgres.crt /postgres.crt
	sudo cp target/postgres.key /postgres.key
	sudo chown 999:999 /postgres.crt /postgres.key

.PHONY: truststore-postgres
truststore-postgres: /postgres.crt

## Start a PostreSQL database container with HTTPS
## -c ssl_ciphers=TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256 cannot control accepted ciphers with tls v1.3
target/docker/.run-docker-db-postgres-https: target/docker/.docker-network /postgres.crt
	docker run \
      -d \
      -e POSTGRES_HOST_AUTH_METHOD=trust \
      -e POSTGRES_DB=$(POSTGRES_DB_NAME) \
      -e POSTGRES_USER=$(POSTGRES_DB_USER) \
      -v /postgres.crt:/postgres.crt:ro \
      -v /postgres.key:/postgres.key:ro \
      --network $(DOCKER_NETWORK) \
      --name $(POSTGRES_CONTAINER_NAME) \
      postgres \
      -c ssl=true\
      -c ssl_cert_file=/postgres.crt\
      -c ssl_key_file=/postgres.key\
      -c ssl_prefer_server_ciphers=true\
      -c ssl_min_protocol_version=TLSv1.3
	@touch $@

.PHONY: run-docker-db-postgres-https
run-docker-db-postgres-https: target/docker/.run-docker-db-postgres-https

## Pre-run - Exchange config.json -----
/etc/horizon/exchange:
	sudo mkdir -p /etc/horizon/exchange

/etc/horizon/exchange/config-http.json: /etc/horizon/exchange
	: $${EXCHANGE_ROOTPW:?}
	sudo -- bash -c "printf \
'{\n'\
'  \"api\": {\n'\
'    \"db\": {\n'\
'      \"jdbcUrl\": \"jdbc:postgresql://$(POSTGRES_CONTAINER_ADDRESS):$(POSTGRES_DB_PORT)/$(POSTGRES_DB_NAME)\",\n'\
'      \"user\": \"$(POSTGRES_DB_USER)\"\n'\
'    },\n'\
'    \"logging\": {\n'\
'      \"level\": \"$(EXCHANGE_LOG_LEVEL)\"\n'\
'    },\n'\
'    \"root\": {\n'\
'      \"password\": \"$(EXCHANGE_ROOTPW)\",\n'\
'      \"frontEndHeader\": \"$(EXCHANGE_FE_HEADER)\"\n'\
'    },\n'\
'    \"service\": {\n'\
'      \"port\": $(EXCHANGE_CONTAINER_PORT_HTTP),\n'\
'      \"portEncrypted\": null\n'\
'    }\n'\
'  }\n'\
'}' > /etc/horizon/exchange/config-http.json"
	sudo chmod o+r /etc/horizon/exchange/config-http.json

/etc/horizon/exchange/config-https.json: /etc/horizon/exchange target/docker/.run-docker-db-postgres-https
	: $${EXCHANGE_ROOTPW:?}
	sudo -- bash -c "printf \
'{\n'\
'  \"api\": {\n'\
'    \"db\": {\n'\
'      \"jdbcUrl\": \"jdbc:postgresql://$(POSTGRES_CONTAINER_ADDRESS):$(POSTGRES_DB_PORT)/$(POSTGRES_DB_NAME)\",\n'\
'      \"user\": \"$(POSTGRES_DB_USER)\"\n'\
'    },\n'\
'    \"logging\": {\n'\
'      \"level\": \"$(EXCHANGE_LOG_LEVEL)\"\n'\
'    },\n'\
'    \"root\": {\n'\
'      \"password\": \"$(EXCHANGE_ROOTPW)\",\n'\
'      \"frontEndHeader\": \"$(EXCHANGE_FE_HEADER)\"\n'\
'    },\n'\
'    \"service\": {\n'\
'      \"port\": $(EXCHANGE_CONTAINER_PORT_HTTP),\n'\
'      \"portEncrypted\": $(EXCHANGE_CONTAINER_PORT_HTTPS)\n'\
'    },\n'\
'    \"tls\": {\n'\
'      \"password\": \"$(EXCHANGE_TRUST_PW)\",\n'\
'      \"truststore\": \"/etc/horizon/exchange/localhost.p12\"\n'\
'    }\n'\
'  }\n'\
'}' > /etc/horizon/exchange/config-https.json"
	sudo chmod o+r /etc/horizon/exchange/config-https.json

## Pre-Run - TLS Truststore -----------
## Only do this once to create the exchange truststore for https (which includes the private key, and cert with multiple names).
$(EXCHANGE_HOST_TRUST_DIR): /etc/horizon/exchange
	mkdir -p $(EXCHANGE_HOST_TRUST_DIR)

## Creates a self-signed TLS certificate for localhost
target/localhost.crt: target/docker/stage/Dockerfile
	openssl req -x509 -days $(EXCHANGE_TRUST_DUR) -out target/localhost.crt -keyout target/localhost.key \
    -newkey rsa:4096 -nodes -sha512 \
    -subj '/CN=localhost' -extensions EXT -config <( \
    printf "[dn]\nCN=localhost\n[req]\ndistinguished_name = dn\n[EXT]\nsubjectAltName=DNS:localhost\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth")


/etc/horizon/exchange/localhost.p12: target/localhost.crt
	openssl pkcs12 -export -out target/localhost.p12 -in target/localhost.crt -inkey target/localhost.key -aes-256-cbc -passout pass:$(EXCHANGE_TRUST_PW)
	chmod o+r target/localhost.p12
	sudo chown root:root target/localhost.p12
	sudo cp -f target/localhost.p12 /etc/horizon/exchange/localhost.p12

.PHONY: truststore
truststore: /etc/horizon/exchange/localhost.p12


# Run -------------------------------------------------------------------------
## Run - Docker -----------------------
## For Continuous Integration testing
target/docker/.run-docker: /etc/horizon/exchange/config-http.json target/docker/.docker-network
	sudo -- bash -c "cp /etc/horizon/exchange/config-http.json /etc/horizon/exchange/config.json"
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTP):$(EXCHANGE_CONTAINER_PORT_HTTP) \
      -v /etc/horizon/exchange/config.json:/etc/horizon/exchange/exchange-api.tmpl:ro \
      $(IMAGE_STRING):$(DOCKER_TAG)
	@touch $@

.PHONY: run-docker
run-docker: target/docker/.run-docker

## config.json is renamed to exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
target/docker/.run-docker-icp-https: /etc/horizon/exchange/config-https.json target/docker/.docker-network /etc/horizon/exchange/localhost.p12 target/docker/.run-docker-db-postgres-https
	sudo -- bash -c "cp /etc/horizon/exchange/config-https.json /etc/horizon/exchange/config.json"
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTP):$(EXCHANGE_CONTAINER_PORT_HTTP) \
      -p $(EXCHANGE_HOST_PORT_HTTPS):$(EXCHANGE_CONTAINER_PORT_HTTPS) \
      -e "JAVA_OPTS=$(JAVA_OPTS)" \
      -e "ICP_EXTERNAL_MGMT_INGRESS=$$ICP_EXTERNAL_MGMT_INGRESS" \
      -v /etc/horizon/exchange/config.json:/etc/horizon/exchange/exchange-api.tmpl:ro \
      -v $(EXCHANGE_HOST_ICP_CERT_FILE):$(EXCHANGE_ICP_CERT_FILE) \
      -v $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12:$(EXCHANGE_CONTAINER_TRUST_DIR)/localhost.p12:ro \
      -v $(EXCHANGE_HOST_POSTGRES_CERT_FILE):$(EXCHANGE_CONTAINER_POSTGRES_CERT_FILE) \
      $(IMAGE_STRING):$(DOCKER_TAG)
	@touch $@

.PHONY: run-docker-icp-https
run-docker-icp-https: target/docker/.run-docker-icp-https

## config.json is mounted into the container as exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Bind-mounting it with read-only permissions prevents the container from attempting to overwrite it.
#
target/docker/.run-docker-icp: /etc/horizon/exchange/config-http.json target/docker/.docker-network
	sudo -- bash -c "cp /etc/horizon/exchange/config-http.json /etc/horizon/exchange/config.json"
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTP):$(EXCHANGE_CONTAINER_PORT_HTTP) \
      -e "JAVA_OPTS=$(JAVA_OPTS)" \
      -e "ICP_EXTERNAL_MGMT_INGRESS=$$ICP_EXTERNAL_MGMT_INGRESS" \
      -v /etc/horizon/exchange/config.json:/etc/horizon/exchange/exchange-api.tmpl:ro \
      $(IMAGE_STRING):$(DOCKER_TAG)
	@touch $@

.PHONY: run-docker-icp
run-docker-icp: target/docker/.run-docker-icp

## Run - Shell  -----------------------
## Build the jar and run it locally in a shell (not .class files in sbt, nor in a docker container)
## Note: this is the same way it is run inside the docker container
.PHONY: run-executable
run-executable: stage
	./target/universal/stage/bin/amd64_exchange-api


# Publish ---------------------------------------------------------------------
## Publish - Docker Hub ---------------
## Push the docker images to the registry w/o rebuilding them
.PHONY: docker-push-only
docker-push-only:
	docker push $(IMAGE_STRING):$(DOCKER_TAG)
	docker tag $(IMAGE_STRING):$(DOCKER_TAG) $(IMAGE_STRING):$(DOCKER_LATEST)
	docker push $(IMAGE_STRING):$(DOCKER_LATEST)

## Promote to prod by retagging to stable and pushing to the docker registry
.PHONY: docker-push-to-prod
docker-push-to-prod:
	docker tag $(IMAGE_STRING):$(DOCKER_TAG) $(IMAGE_STRING):stable
	docker push $(IMAGE_STRING):stable

## Push the image with the explicit version tag (so someone else can test it), but do not push the 'latest' tag so it does not get deployed to stg yet
.PHONY: docker-push-version-only
docker-push-version-only:
	docker push $(IMAGE_STRING):$(DOCKER_TAG)


# Test  -----------------------------------------------------------------------
# Local test
# Must an Exchange instance running locally or in docker
.PHONY: test
test:
	: $${EXCHANGE_ROOTPW:?}   # this verifies these env vars are set
	sbt test


# Cleanup -------------------------------------------------------------target/docker/.docker-run-icp--------
## Cleanup - Docker -------------------
.PHONY: clean-docker
clean-docker:
	docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker rm -f $(POSTGRES_CONTAINER_NAME) 2> /dev/null || :
	docker network remove $(DOCKER_NETWORK) 2> /dev/null || :
	rm -f target/docker/.docker-network target/docker/.run-docker target/docker/.run-docker-db-postgres-https target/docker/.run-docker-icp target/docker/.run-docker-icp-https
	sudo rm -fr /postgres.crt /postgres.key target/postgres.crt target/postgres.key

.PHONY: cleaner-docker
cleaner-docker: clean-docker
	docker rmi -f $(IMAGE_STRING)
	rm -fr target/docker

.PHONY: cleanest-docker
cleanest-docker: cleaner-docker
	docker system prune -af

## Cleanup - Truststore ---------------
.PHONY: clean-truststore
clean-truststore:
	sudo rm -f target/*.p12 /etc/horizon/exchange/*.p12

.PHONY: cleaner-truststore
cleaner-truststore: clean-truststore
	sudo rm -f target/*.crt target/*.key

.PHONY: cleanest-truststore
cleanest-truststore: cleaner-truststore

## Cleanup - All ----------------------
.PHONY: clean
clean: clean-docker clean-truststore
	sbt clean

.PHONY: cleaner
cleaner: clean cleaner-docker cleaner-truststore
	sudo rm -fr /etc/horizon/exchange/config*.json

.PHONY: cleanest
cleanest: cleaner cleanest-docker cleanest-truststore
	sudo rm -fr ./target ./bin
