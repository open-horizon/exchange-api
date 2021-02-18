# Make targets for the Exchange REST API server

SHELL = /bin/bash -e
DOCKER_REGISTRY ?= openhorizon
ARCH ?= amd64
DOCKER_NAME ?= amd64_exchange-api
VERSION = $(shell cat src/main/resources/version.txt)
DOCKER_NETWORK=exchange-api-network
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
COMPILE_CLEAN ?= clean
image-string = $(DOCKER_REGISTRY)/$(ARCH)_exchange-api

# Some of these vars are also used by the Dockerfiles
# try to sync this version with the version of scala you have installed on your dev machine, and with what is specified in build.sbt
SCALA_VERSION ?= 2.12.10
#SCALA_VERSION_SHORT ?= 2.12
# this version corresponds to the Version variable in project/build.scala
#EXCHANGE_API_WAR_VERSION ?= 0.1.0
EXCHANGE_API_DIR ?= /src/github.com/open-horizon/exchange-api
EXCHANGE_API_PORT ?= 8080
EXCHANGE_API_HTTPS_PORT ?= 8443
# Location of config.json and icp/ca.crt in the container
EXCHANGE_CONFIG_DIR ?= /etc/horizon/exchange
EXCHANGE_ICP_CERT_FILE ?= /etc/horizon/exchange/icp/ca.crt
OS := $(shell uname)
ifeq ($(OS),Darwin)
  # Mac OS X
  EXCHANGE_HOST_CONFIG_DIR ?= /private$(EXCHANGE_CONFIG_DIR)/docker
  EXCHANGE_HOST_ICP_CERT_FILE ?= /private$(EXCHANGE_ICP_CERT_FILE)
else
  # Assume Linux (could test by test if OS is Linux)
  EXCHANGE_HOST_CONFIG_DIR ?= $(EXCHANGE_CONFIG_DIR)
  EXCHANGE_HOST_ICP_CERT_FILE ?= $(EXCHANGE_ICP_CERT_FILE)
endif
# Location of the ssl key/cert that it should use so it can serve https routes. This need to be fully qualified, so docker can mount it into the container for jetty to access
EXCHANGE_HOST_KEYSTORE_DIR ?= $(PWD)/keys/etc
EXCHANGE_HOST_ALIAS ?= edge-fab-exchange
EXCHANGE_CONTAINER_KEYSTORE_DIR ?= /var/lib/jetty/etc
# The public cert we should use to connect to an ibm cloud postgres db
EXCHANGE_HOST_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_CONFIG_DIR)/postres-cert/root.crt
# Note: this home dir in the container must match what is set for daemonUser in build.sbt
EXCHANGE_CONTAINER_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_POSTGRES_CERT_FILE) 
# /home/exchangeuser/.postgresql/root.crt
# Use this to pass args to the exchange svr JVM by overriding JAVA_OPTS in your environment
export JAVA_OPTS ?=
#export JAVA_OPTS ?= -Xmx1G

default: .docker-exec-run

clean:
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	- docker rmi $(image-string):{$(DOCKER_TAG),$(DOCKER_LATEST)} 2> /dev/null || :
	- docker rm -f $(DOCKER_NAME)_test 2> /dev/null || :
	- docker rmi $(image-string):test 2> /dev/null || :
	- docker network remove $(DOCKER_NETWORK) 2> /dev/null || :
	rm -f .docker-*

# Altho the name is a little misleading, .docker-exec just means build the container used for running the exchange
docker: .docker-exec

# Note: Using dot files to hold the modification time the docker image/container was built

# Both of these cmds can fail for legitimate reasons:
#  - the remove because the network is not there or the test container is currently running and using it
#  - the create because the network already exists because of the build step
.docker-network:
	- docker network remove $(DOCKER_NETWORK) 2> /dev/null || :
	docker network create $(DOCKER_NETWORK)
	@touch $@

.docker-exec: src/main/scala/com/horizon/exchangeapi/*.scala src/main/scala/com/horizon/exchangeapi/auth/*.scala src/main/scala/com/horizon/exchangeapi/tables/*.scala
	sbt docker:publishLocal
	@touch $@

# config.json is mounted into the container as exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
.docker-exec-run: .docker-exec .docker-network
	@if [[ ! -f "$(EXCHANGE_HOST_KEYSTORE_DIR)/keystore" || ! -f "$(EXCHANGE_HOST_KEYSTORE_DIR)/keypassword" ]]; then echo "Error: keystore and keypassword do not exist in $(EXCHANGE_HOST_KEYSTORE_DIR). You must first copy them there or run 'make gen-key'"; false; fi
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker run --name $(DOCKER_NAME) --network $(DOCKER_NETWORK) -d -t -p $(EXCHANGE_API_PORT):$(EXCHANGE_API_PORT) -p $(EXCHANGE_API_HTTPS_PORT):$(EXCHANGE_API_HTTPS_PORT) -e "JAVA_OPTS=$(JAVA_OPTS)" -e "ICP_EXTERNAL_MGMT_INGRESS=$$ICP_EXTERNAL_MGMT_INGRESS" -v $(EXCHANGE_HOST_CONFIG_DIR)/config.json:$(EXCHANGE_CONFIG_DIR)/exchange-api.tmpl:ro -v $(EXCHANGE_HOST_ICP_CERT_FILE):$(EXCHANGE_ICP_CERT_FILE) -v $(EXCHANGE_HOST_KEYSTORE_DIR):$(EXCHANGE_CONTAINER_KEYSTORE_DIR):ro -v $(EXCHANGE_HOST_POSTGRES_CERT_FILE):$(EXCHANGE_CONTAINER_POSTGRES_CERT_FILE) $(image-string):$(DOCKER_TAG)
	@touch $@

# Note: this target is used by travis as part of testing
# config.json is mounted into the container as exchange-api.tmpl to overwrite the provided file of the same name in the Docker image. Prevents the container from attempting to overwrite a bind-mounted config.json with read-only permissions.
.docker-exec-run-no-https: .docker-exec .docker-network
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker run --name $(DOCKER_NAME) --network $(DOCKER_NETWORK) -d -t -p $(EXCHANGE_API_PORT):$(EXCHANGE_API_PORT) -e "JAVA_OPTS=$(JAVA_OPTS)" -v $(EXCHANGE_HOST_CONFIG_DIR)/config.json:$(EXCHANGE_CONFIG_DIR)/exchange-api.tmpl $(image-string):$(DOCKER_TAG)
	@touch $@

# Build the executable and run it locally (not in sbt and not in docker)
# Note: this is the same way it is run inside the docker container
runexecutable:
	sbt stage
	./target/universal/stage/bin/exchange-api

# Run the automated tests in the test container against the exchange svr running in the exec container
# Note: these targets is used by travis as part of testing
#someday: can we create an "excutable" of the tests using sbt-native-packager instead?
.docker-test: .docker-network
	docker build -t $(image-string):test $(DOCKER_OPTS) -f Dockerfile-test --build-arg SCALA_VERSION=$(SCALA_VERSION) .
	@touch $@

test:
	: $${EXCHANGE_ROOTPW:?}   # this verifies these env vars are set
	sbt test

# Push the docker images to the registry w/o rebuilding them
docker-push-only:
	docker push $(image-string):$(DOCKER_TAG)
	docker tag $(image-string):$(DOCKER_TAG) $(image-string):$(DOCKER_LATEST)
	docker push $(image-string):$(DOCKER_LATEST)

# Push the image with the explicit version tag (so someone else can test it), but do not push the 'latest' tag so it does not get deployed to stg yet
docker-push-version-only:
	docker push $(image-string):$(DOCKER_TAG)

docker-push: docker docker-push-only

# Promote to prod by retagging to stable and pushing to the docker registry
docker-push-to-prod:
	docker tag $(image-string):$(DOCKER_TAG) $(image-string):stable
	docker push $(image-string):stable

# Only do this once to create the exchange keystore for https (which includes the private key, and cert with multiple names). Keytool recommends pkcs12 format.
# You must first set EXCHANGE_KEY_PW to the password you want to use for the private key and for the keystore.
# References:
#   https://www.eclipse.org/jetty/documentation/9.4.x/configuring-ssl.html
#   https://docs.oracle.com/javase/8/docs/technotes/tools/windows/keytool.html
#   https://stackoverflow.com/questions/8744607/how-to-add-subject-alernative-name-to-ssl-certs
#   https://wiki.eclipse.org/Jetty/Howto/Secure_Passwords
gen-key:
	@if [[ -f "$(EXCHANGE_HOST_KEYSTORE_DIR)/keystore" ]]; then echo "Error: $(EXCHANGE_HOST_KEYSTORE_DIR)/keystore already exists. If you really want to regenerate it, manually delete it first."; false; fi
	: $${EXCHANGE_KEY_PW:?}
	mkdir -p $(EXCHANGE_HOST_KEYSTORE_DIR)
	@echo "Generating exchange keystore and public certificate for https..."
	# the arg -ext san=dns:<hostname>,ip:<ip> specify additional hostnames/IPs this cert should apply to
	keytool -genkey -noprompt -alias $(EXCHANGE_HOST_ALIAS) -keyalg RSA -sigalg SHA256withRSA -dname "CN=$(EXCHANGE_HOST_ALIAS), OU=Edge, O=IBM, L=Unknown, S=Unknown, C=US" -keystore $(EXCHANGE_HOST_KEYSTORE_DIR)/keystore -storetype pkcs12 -storepass '$(value EXCHANGE_KEY_PW)' -keypass '$(value EXCHANGE_KEY_PW)' -validity 3650
	# extract the public certificate out of the keystore, for clients to use
	keytool -keystore $(EXCHANGE_HOST_KEYSTORE_DIR)/keystore -storepass '$(value EXCHANGE_KEY_PW)' -keypass '$(value EXCHANGE_KEY_PW)' -export -alias $(EXCHANGE_HOST_ALIAS) -rfc -file $(EXCHANGE_HOST_KEYSTORE_DIR)/exchangecert.pem
	# put salted pw in file so it can be used later by the exchange to access the keystore
	docker run --rm -t jetty:9.4 /bin/bash -c 'java -cp $$JETTY_HOME/lib/jetty-util-$$JETTY_VERSION.jar org.eclipse.jetty.util.security.Password '\''$(value EXCHANGE_KEY_PW)'\''' | grep -E '^OBF:' | tr -d '\n' > $(EXCHANGE_HOST_KEYSTORE_DIR)/keypassword
	# display what we created
	keytool -keystore $(EXCHANGE_HOST_KEYSTORE_DIR)/keystore -storepass '$(value EXCHANGE_KEY_PW)' -list -alias $(EXCHANGE_HOST_ALIAS)
	@echo ""
	@echo "Keystore created. Mount $(EXCHANGE_HOST_KEYSTORE_DIR) into the exchange container. Copy $(EXCHANGE_HOST_KEYSTORE_DIR)/exchangecert.pem to exchange clients using https."

# Get the latest version of the swagger ui from github and copy the dist dir into our repo in src/main/resources/swagger. That dir
# is loaded by SwaggerDocService.scala:SwaggerUiService
sync-swagger-ui:
	rm -rf /tmp/swagger-ui.backup
	mkdir -p /tmp/swagger-ui.backup
	cp -a src/main/resources/swagger/* /tmp/swagger-ui.backup    # backup the version of swagger-ui we are currently using, in case the newer verion does not work
	git -C ../../swagger-api/swagger-ui pull    # update the repo
	cp -a ../../swagger-api/swagger-ui/dist/ src/main/resources/swagger      # copy the latest dist dir from the repo into our repo
	sed -e 's|https://petstore.swagger.io/v2/swagger.json|/v1/api-docs/swagger.json|' src/main/resources/swagger/index.html > /tmp/swagger-index.html
	mv /tmp/swagger-index.html src/main/resources/swagger/index.html

testmake:
	@echo "BRANCH=$(BRANCH)"
	@echo "DOCKER_TAG=$(DOCKER_TAG)"
	@echo "DOCKER_LATEST=$(DOCKER_LATEST)"

version:
	@echo $(VERSION)

.SECONDARY:

.PHONY: default clean docker runexecutable test docker-push-only docker-push-version-only docker-push docker-push-to-prod gen-key sync-swagger-ui testmake version
