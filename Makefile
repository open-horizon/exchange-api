# Make targets for the Exchange REST API server

SHELL = /bin/bash -e

# Some of these vars are also used by the Dockerfiles
ARCH ?= amd64
DOCKER_NAME ?= amd64_exchange-api
DOCKER_NETWORK = exchange-api-network
DOCKER_REGISTRY ?= openhorizon
EXCHANGE_VERSION = $(shell cat src/main/resources/version.txt)
#todo: get this value from the target git branch
TARGET_BRANCH ?=
ifneq ($(TARGET_BRANCH),)
  BRANCH = -$(TARGET_BRANCH)
else
  BRANCH =
endif
DOCKER_TAG ?= $(EXCHANGE_VERSION)$(BRANCH)
DOCKER_LATEST ?= latest$(BRANCH)
DOCKER_OPTS ?= --no-cache
COMPILE_CLEAN ?= clean
IMAGE_STRING = $(DOCKER_REGISTRY)/$(ARCH)_exchange-api
EXCHANGE_API_DIR ?= /src/github.com/open-horizon/exchange-api
# This version corresponds to the Version variable in project/build.scala
# EXCHANGE_API_WAR_VERSION ?= 0.1.0
# Location of config.json and icp/ca.crt in the container
EXCHANGE_CONFIG_DIR ?= $(PROJECT_DIRECTORY)/target/etc/horizon/exchange
EXCHANGE_CONTAINER_CONFIG_DIR ?= /etc/horizon/exchange
EXCHANGE_CONTAINER_PORT_HTTP ?= null
EXCHANGE_CONTAINER_PORT_HTTPS ?= 8080
# Note: this home dir in the container must match what is set for daemonUser in build.sbt
EXCHANGE_CONTAINER_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_POSTGRES_CERT_FILE)
EXCHANGE_CONTAINER_TRUST_DIR ?= /etc/horizon/exchange
EXCHANGE_FE_HEADER ?= issuer
EXCHANGE_HOST_ALIAS ?= edge-fab-exchange

ifeq ($(shell uname),Darwin)
  # Mac OS X
  EXCHANGE_HOST_CONFIG_DIR ?= /private$(EXCHANGE_CONFIG_DIR)/docker
  EXCHANGE_HOST_ICP_CERT_FILE ?= /private$(EXCHANGE_ICP_CERT_FILE)
else
  # Assume Linux (could test by test if OS is Linux)
  EXCHANGE_HOST_CONFIG_DIR ?= $(EXCHANGE_CONFIG_DIR)
  EXCHANGE_HOST_ICP_CERT_FILE ?= $(EXCHANGE_ICP_CERT_FILE)
endif

EXCHANGE_HOST_PORT_HTTP ?= null
EXCHANGE_HOST_PORT_HTTPS ?= 8080
# The public cert we should use to connect to an ibm cloud postgres db
EXCHANGE_HOST_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_CONFIG_DIR)/postres-cert/root.crt
# Directory location of the SSL truststore that is used to serve Https traffic. This need to be fully qualified, so docker can mount it into the container for the Exchange to access.
EXCHANGE_HOST_TRUST_DIR ?= $(PROJECT_DIRECTORY)/target/etc/horizon/exchange/truststore
EXCHANGE_ICP_CERT_FILE ?= /etc/horizon/exchange/icp/ca.crt
# Set to "DEBUG" to turn on debugging
EXCHANGE_LOG_LEVEL ?= INFO
# Number of days the SSL certificate is valid for
EXCHANGE_TRUST_DUR ?= 1
# Use this to pass args to the exchange svr JVM by overriding JAVA_OPTS in your environment
JAVA_OPTS ?=#-Xmx1G
POSTGRES_CONTAINER_ADDRESS ?= $(shell docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(POSTGRES_CONTAINER_NAME))
POSTGRES_CONTAINER_NAME ?= exchange-postgres
POSTGRES_DB_NAME ?= exchange
POSTGRES_DB_PORT ?= 5432
POSTGRES_DB_USER ?= admin
PROJECT_DIRECTORY ?= $(shell pwd)
# Try to sync this version with the version of scala you have installed on your dev machine, and with what is specified in build.sbt
SCALA_VERSION ?= 2.12.10
SCALA_VERSION_SHORT ?= 2.12


.PHONY: default
default: docker-exec-run


# Utility ---------------------------------------------------------------------
.PHONY: testmake
testmake:
	@echo "BRANCH=$(BRANCH)"
	@echo "DOCKER_TAG=$(DOCKER_TAG)"
	@echo "DOCKER_LATEST=$(DOCKER_LATEST)"

.PHONY: version
version:
	@echo $(EXCHANGE_VERSION)


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
.PHONY: docker-exec
docker-exec:
	sbt docker:publishLocal

.PHONY: docker
docker: docker-exec


# Pre-Run  --------------------------------------------------------------------
.PHONY: docker-network
docker-network:
	docker network create $(DOCKER_NETWORK)

.PHONY: docker-run-dev-db-postgres
docker-run-dev-db-postgres: docker-network
	docker run \
      -d \
      -e POSTGRES_HOST_AUTH_METHOD=trust \
      -e POSTGRES_DB=$(POSTGRES_DB_NAME) \
      -e POSTGRES_USER=$(POSTGRES_DB_USER) \
      --network $(DOCKER_NETWORK) \
      --name $(POSTGRES_CONTAINER_NAME) \
      postgres

$(EXCHANGE_CONFIG_DIR):
	mkdir -p $(EXCHANGE_CONFIG_DIR)

$(EXCHANGE_CONFIG_DIR)/config.json: $(EXCHANGE_CONFIG_DIR) docker-run-dev-db-postgres
	: $${EXCHANGE_ROOTPW:?}
	: $${EXCHANGE_TRUST_PW:?}
	printf \
'{\n'\
'  "api": {\n'\
'    "db": {\n'\
'      "jdbcUrl": "jdbc:postgresql://$(POSTGRES_CONTAINER_ADDRESS):$(POSTGRES_DB_PORT)/$(POSTGRES_DB_NAME)",\n'\
'      "user": "$(POSTGRES_DB_USER)"\n'\
'    },\n'\
'    "logging": {'\
'      "level": "$(EXCHANGE_LOG_LEVEL)"'\
'    },'\
'    "root": {\n'\
'      "password": "$(EXCHANGE_ROOTPW)",\n'\
'      "frontEndHeader": "$(EXCHANGE_FE_HEADER)"\n'\
'    },\n'\
'    "service": {\n'\
'      "portEncrypted": $(EXCHANGE_CONTAINER_PORT_HTTPS),\n'\
'      "portUnencrypted": $(EXCHANGE_CONTAINER_PORT_HTTP)\n'\
'    },\n'\
'    "ssl": {\n'\
'      "location": "$(EXCHANGE_CONTAINER_TRUST_DIR)/localhost.p12",\n'\
'      "password": "$(EXCHANGE_TRUST_PW)"\n'\
'    }\n'\
'  }\n'\
'}' > $(EXCHANGE_CONFIG_DIR)/config.json
	chmod o+r $(EXCHANGE_CONFIG_DIR)/config.json

# Pre-Run - SSL Truststore ------------
## Only do this once to create the exchange truststore for https (which includes the private key, and cert with multiple names).
$(EXCHANGE_HOST_TRUST_DIR): $(EXCHANGE_CONFIG_DIR)
	mkdir -p $(EXCHANGE_HOST_TRUST_DIR)

# Creates a self-signed SSL certificate for localhost
$(EXCHANGE_HOST_TRUST_DIR)/localhost.crt: $(EXCHANGE_HOST_TRUST_DIR)
	openssl req -x509 -days $(EXCHANGE_TRUST_DUR) -out $(EXCHANGE_HOST_TRUST_DIR)/localhost.crt -keyout $(EXCHANGE_HOST_TRUST_DIR)/localhost.key \
    -newkey rsa:4096 -nodes -sha512 \
    -subj '/CN=localhost' -extensions EXT -config <( \
    printf "[dn]\nCN=localhost\n[req]\ndistinguished_name = dn\n[EXT]\nsubjectAltName=DNS:localhost\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth")

$(EXCHANGE_HOST_TRUST_DIR)/localhost.p12: $(EXCHANGE_HOST_TRUST_DIR)/localhost.crt
	openssl pkcs12 -export -out $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12 -in $(EXCHANGE_HOST_TRUST_DIR)/localhost.crt -inkey $(EXCHANGE_HOST_TRUST_DIR)/localhost.key -aes256 -passout env:EXCHANGE_TRUST_PW
	chmod o+r $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12

.PHONY: truststore
truststore: $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12


# Run -------------------------------------------------------------------------
# Run - Docker ------------------------
# config.json is renamed to exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
.PHONY: .docker-exec-run
docker-exec-run: docker-exec docker-network
	@if [[ ! -f "$(EXCHANGE_HOST_TRUST_DIR)/localhost.p12"]]; then echo "Error: keystore and keypassword do not exist in $(EXCHANGE_HOST_TRUST_DIR). You must first copy them there or run 'make truststore'"; false; fi
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTPS):$(EXCHANGE_CONTAINER_PORT_HTTPS) \
      -e "JAVA_OPTS=$(JAVA_OPTS)" \
      -e "ICP_EXTERNAL_MGMT_INGRESS=$$ICP_EXTERNAL_MGMT_INGRESS" \
      -v $(EXCHANGE_HOST_CONFIG_DIR)/config.json:$(EXCHANGE_CONTAINER_CONFIG_DIR)/exchange-api.tmpl:ro \
      -v $(EXCHANGE_HOST_ICP_CERT_FILE):$(EXCHANGE_ICP_CERT_FILE) \
      -v $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12:$(EXCHANGE_CONTAINER_TRUST_DIR)/localhost.p12:ro \
      # -v $(EXCHANGE_HOST_POSTGRES_CERT_FILE):$(EXCHANGE_CONTAINER_POSTGRES_CERT_FILE) \
      $(image-string):$(DOCKER_TAG)

# Note: this target is used by Travis CI's automated testing
# config.json is renamed to exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
.PHONY: docker-run-dev
docker-run-dev: $(EXCHANGE_CONFIG_DIR)/config.json docker-exec docker-run-dev-db-postgres truststore
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTPS):$(EXCHANGE_CONTAINER_PORT_HTTPS) \
      -e "JAVA_OPTS=$(JAVA_OPTS)" \
      -v $(EXCHANGE_HOST_CONFIG_DIR)/config.json:$(EXCHANGE_CONTAINER_CONFIG_DIR)/exchange-api.tmpl:ro \
      -v $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12:$(EXCHANGE_CONTAINER_TRUST_DIR)/localhost.p12:ro \
      $(IMAGE_STRING):$(DOCKER_TAG)

# config.json is renamed to exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
.PHONY: docker-run-dev-with-http
docker-run-dev-with-http: $(EXCHANGE_CONFIG_DIR)/config.json docker-exec docker-run-dev-db-postgres truststore
	docker run \
      --name $(DOCKER_NAME) \
      --network $(DOCKER_NETWORK) \
      -d -t \
      -p $(EXCHANGE_HOST_PORT_HTTP):$(EXCHANGE_CONTAINER_PORT_HTTP) \
      -p $(EXCHANGE_HOST_PORT_HTTPS):$(EXCHANGE_CONTAINER_PORT_HTTPS) \
      -e "JAVA_OPTS=$(JAVA_OPTS)" \
      -v $(EXCHANGE_HOST_CONFIG_DIR)/config.json:$(EXCHANGE_CONTAINER_CONFIG_DIR)/exchange-api.tmpl:ro \
      -v $(EXCHANGE_HOST_TRUST_DIR)/localhost.p12:$(EXCHANGE_CONTAINER_TRUST_DIR)/localhost.p12:ro \
      $(IMAGE_STRING):$(DOCKER_TAG)


# Publish ---------------------------------------------------------------------
# Publish - Docker Hub ----------------
# Push the docker images to the registry w/o rebuilding them
.PHONY: docker-push-only
docker-push-only:
	docker push $(IMAGE_STRING):$(DOCKER_TAG)
	docker tag $(IMAGE_STRING):$(DOCKER_TAG) $(IMAGE_STRING):$(DOCKER_LATEST)
	docker push $(IMAGE_STRING):$(DOCKER_LATEST)

.PHONY: docker-push
docker-push: docker docker-push-only

# Promote to prod by retagging to stable and pushing to the docker registry
.PHONY: docker-push-to-prod
docker-push-to-prod:
	docker tag $(IMAGE_STRING):$(DOCKER_TAG) $(IMAGE_STRING):stable
	docker push $(IMAGE_STRING):stable

# Push the image with the explicit version tag (so someone else can test it), but do not push the 'latest' tag so it does not get deployed to stg yet
.PHONY: docker-push-version-only
docker-push-version-only:
	docker push $(IMAGE_STRING):$(DOCKER_TAG)


# Test  -----------------------------------------------------------------------
.PHONY: test
test:
	: $${EXCHANGE_ROOTPW:?}   # this verifies these env vars are set
	sbt test


# Cleanup ---------------------------------------------------------------------
# Cleanup - Docker --------------------
.PHONY: docker-clean
docker-clean:
	docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker rm -f $(POSTGRES_CONTAINER_NAME) 2> /dev/null || :
	docker network remove $(DOCKER_NETWORK) 2> /dev/null || :

.PHONY: docker-cleaner
docker-cleaner: docker-clean
	docker rmi -f $(IMAGE_STRING)

.PHONY: docker-cleanest
docker-cleanest: docker-cleaner
	docker system prune -af

# Cleanup - Truststore ----------------
.PHONY: truststore-clean
truststore-clean:
	rm -f $(EXCHANGE_HOST_TRUST_DIR)/*.p12

.PHONY: truststore-cleaner
truststore-cleaner: truststore-clean
	rm -f $(EXCHANGE_HOST_TRUST_DIR)/*.crt $(EXCHANGE_HOST_TRUST_DIR)/*.key

.PHONY: truststore-cleanest
truststore-cleanest: truststore-cleaner
	rm -fr $(EXCHANGE_HOST_TRUST_DIR)

# Cleanup - All -----------------------
.PHONY: clean
clean: docker-clean truststore-clean
	sbt clean

.PHONY: cleaner
cleaner: clean docker-cleaner truststore-cleaner
	rm -fr $(EXCHANGE_CONFIG_DIR)

.PHONY: cleanest
cleanest: cleaner docker-cleanest truststore-cleanest
	rm -fr ./target ./bin
