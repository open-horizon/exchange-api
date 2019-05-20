# Make targets for the Exchange REST API server

SHELL = /bin/bash -e
DOCKER_REGISTRY ?= openhorizon
ARCH ?= amd64
DOCKER_NAME ?= exchange-api
VERSION = $(shell cat src/main/resources/version.txt)
DOCKER_NETWORK=exchange-api-network
#todo: when the travis test is working again, get this value from the target git branch
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
JETTY_BASE_VERSION ?= 9.4
# try to sync this version with the version of scala you have installed on your dev machine, and with what is specified in build.sbt
SCALA_VERSION ?= 2.12.7
SCALA_VERSION_SHORT ?= 2.12
# this version corresponds to the Version variable in project/build.scala
EXCHANGE_API_WAR_VERSION ?= 0.1.0
EXCHANGE_API_DIR ?= /src/github.com/open-horizon/exchange-api
EXCHANGE_API_PORT ?= 8080
EXCHANGE_API_HTTPS_PORT ?= 8443
EXCHANGE_CONFIG_DIR ?= /etc/horizon/exchange
OS := $(shell uname)
ifeq ($(OS),Darwin)
  # Mac OS X
  EXCHANGE_HOST_CONFIG_DIR ?= /private$(EXCHANGE_CONFIG_DIR)/docker
else
  # Assume Linux (could test by test if OS is Linux)
  EXCHANGE_HOST_CONFIG_DIR ?= $(EXCHANGE_CONFIG_DIR)
endif
# Location of the ssl key/cert so we can mount it into the container for jetty to access
#EXCHANGE_HOST_KEYSTORE ?= $(PWD)/keys/keystore.pkcs12
#EXCHANGE_CONTAINER_KEYSTORE ?= /var/lib/jetty/etc/keystore
# this need to be fully qualified, so docker can mount it into the container
EXCHANGE_HOST_KEYSTORE_DIR ?= $(PWD)/keys/etc
EXCHANGE_HOST_ALIAS ?= edge-fab-exchange
EXCHANGE_CONTAINER_KEYSTORE_DIR ?= /var/lib/jetty/etc
EXCHANGE_HOST_POSTGRES_CERT_FILE ?= $(EXCHANGE_HOST_CONFIG_DIR)/postres-cert/root.crt
EXCHANGE_CONTAINER_POSTGRES_CERT_FILE ?= /home/jetty/.postgresql/root.crt


default: .docker-exec-run

# Removes exec container, but not bld container
clean: clean-exec-image

clean-exec-image:
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	- docker rmi $(image-string):{$(DOCKER_TAG),$(DOCKER_LATEST)} 2> /dev/null || :
	rm -f .docker-exec .docker-exec-run

# Also remove the bld image/container
clean-all: clean
	- docker rm -f $(DOCKER_NAME)_bld 2> /dev/null || :
	- docker rmi $(image-string):bld 2> /dev/null || :
	- docker network remove $(DOCKER_NETWORK) 2> /dev/null || :
	rm -f .docker-bld .docker-network

# rem-docker-bld:
# 	- docker rm -f $(DOCKER_NAME)_bld 2> /dev/null || :

docker: .docker-exec

# Both of these cmds can fail for legitimate reasons:
#  - the remove because the network is not there or the bld container is currently running and using it
#  - the create because the network already exists because of the build step
.docker-network:
	-docker network remove $(DOCKER_NETWORK) 2> /dev/null || :
	docker network create $(DOCKER_NETWORK)
	@touch $@

# Using dot files to hold the modification time the docker image and container were built
.docker-bld: .docker-network
	docker build -t $(image-string):bld $(DOCKER_OPTS) -f Dockerfile-bld --build-arg SCALA_VERSION=$(SCALA_VERSION) .
	- docker rm -f $(DOCKER_NAME)_bld 2> /dev/null || :
	docker run --name $(DOCKER_NAME)_bld --network $(DOCKER_NETWORK) -d -t -v $(CURDIR):$(EXCHANGE_API_DIR) $(image-string):bld /bin/bash
	@touch $@

.docker-compile: $(wildcard src/main/scala/com/horizon/exchangeapi/*) $(wildcard src/main/resources/*) .docker-bld
	docker exec -t $(DOCKER_NAME)_bld /bin/bash -c "cd $(EXCHANGE_API_DIR) && sbt package"
	# war file ends up in: ./target/scala-$SCALA_VERSION_SHORT/exchange-api_$SCALA_VERSION_SHORT-$EXCHANGE_API_WAR_VERSION.war
	@touch $@

.docker-exec: .docker-compile
	docker pull jetty:$(JETTY_BASE_VERSION)
	docker build -t $(image-string):$(DOCKER_TAG) $(DOCKER_OPTS) -f Dockerfile-exec --build-arg JETTY_BASE_VERSION=$(JETTY_BASE_VERSION) --build-arg SCALA_VERSION=$(SCALA_VERSION) --build-arg SCALA_VERSION_SHORT=$(SCALA_VERSION_SHORT) --build-arg EXCHANGE_API_WAR_VERSION=$(EXCHANGE_API_WAR_VERSION) .
	@touch $@

# rem-docker-exec:
# 	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :

.docker-exec-run: .docker-exec
	@if [[ ! -f "$(EXCHANGE_HOST_KEYSTORE_DIR)/keystore" || ! -f "$(EXCHANGE_HOST_KEYSTORE_DIR)/keypassword" ]]; then echo "Error: keystore and keypassword do not exist in $(EXCHANGE_HOST_KEYSTORE_DIR). You must first copy them there or run 'make gen-key'"; false; fi
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker run --name $(DOCKER_NAME) --network $(DOCKER_NETWORK) -d -t -p $(EXCHANGE_API_PORT):$(EXCHANGE_API_PORT) -p $(EXCHANGE_API_HTTPS_PORT):$(EXCHANGE_API_HTTPS_PORT) -v $(EXCHANGE_HOST_CONFIG_DIR):$(EXCHANGE_CONFIG_DIR) -v $(EXCHANGE_HOST_KEYSTORE_DIR):$(EXCHANGE_CONTAINER_KEYSTORE_DIR):ro -v $(EXCHANGE_HOST_POSTGRES_CERT_FILE):$(EXCHANGE_CONTAINER_POSTGRES_CERT_FILE) $(image-string):$(DOCKER_TAG)
	@touch $@

.docker-exec-run-no-https: .docker-exec
	- docker rm -f $(DOCKER_NAME) 2> /dev/null || :
	docker run --name $(DOCKER_NAME) --network $(DOCKER_NETWORK) -d -t -p $(EXCHANGE_API_PORT):$(EXCHANGE_API_PORT) -v $(EXCHANGE_HOST_CONFIG_DIR):$(EXCHANGE_CONFIG_DIR) $(image-string):$(DOCKER_TAG)
	@touch $@

# Run the automated tests in the bld container against the exchange svr running in the exec container
test: .docker-bld
	: $${EXCHANGE_ROOTPW:?}   # this verifies these env vars are set
	docker exec -t \
		-e EXCHANGE_URL_ROOT=http://$(DOCKER_NAME):8080 \
		-e "EXCHANGE_ROOTPW=$$EXCHANGE_ROOTPW" \
		-e "EXCHANGE_IAM_KEY=$$EXCHANGE_IAM_KEY" \
		-e "EXCHANGE_IAM_EMAIL=$$EXCHANGE_IAM_EMAIL" \
		-e "EXCHANGE_IAM_ACCOUNT=$$EXCHANGE_IAM_ACCOUNT" \
		$(DOCKER_NAME)_bld /bin/bash -c 'cd $(EXCHANGE_API_DIR) && sbt test'

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

# Get the latest version of the swagger ui from github and copy the dist dir into our repo
sync-swagger-ui:
	rm -rf /tmp/swagger-ui.backup
	mkdir -p /tmp/swagger-ui.backup
	cp -a src/main/webapp/* /tmp/swagger-ui.backup    # backup the version of swagger-ui we are currently using, in case the newer verion does not work
	git -C ../../swagger-api/swagger-ui pull    # update the repo
	mv src/main/webapp/index.html src/main/webapp/our-index.html   # we have our own main index.html, so move it out of the way temporaily
	rsync -aiu ../../swagger-api/swagger-ui/dist/ src/main/webapp      # copy the latest dist dir from the repo into our repo
	mv src/main/webapp/index.html src/main/webapp/swagger-index.html
	mv src/main/webapp/our-index.html src/main/webapp/index.html
	#sed -i '' 's/\(new SwaggerUi({\) *$$/\1 validatorUrl: null,/' src/main/webapp/swagger-index.html   # this is the only way to set validatorUrl to null in swagger

testmake:
	@echo "BRANCH=$(BRANCH)"
	@echo "DOCKER_TAG=$(DOCKER_TAG)"
	@echo "DOCKER_LATEST=$(DOCKER_LATEST)"

version:
	@echo $(VERSION)

.SECONDARY:

.PHONY: default clean clean-exec-image clean-all docker test docker-push-only docker-push-version-only docker-push docker-push-to-prod sync-swagger-ui testmake version
