# Build the performance/scale test drivers for the exchange

# To cross-compile for linux from mac: GOOS=linux make $GOOS/node
OS := $(shell uname)
ifeq ($(OS),Darwin)
  # Mac OS X
  export GOOS ?= darwin
else
  # Assume Linux (could test by test if OS is Linux)
  export GOOS ?= linux
endif

all: darwin/node linux/node darwin/agbot linux/agbot

darwin/node: node/node.go perfutils/perfutils.go
	mkdir -p $(shell dirname $@)
	GOOS=$(shell dirname $@) go build -o $@ $<

linux/node: node/node.go perfutils/perfutils.go
	mkdir -p $(shell dirname $@)
	GOOS=$(shell dirname $@) go build -o $@ $<

darwin/agbot: agbot/agbot.go perfutils/perfutils.go
	mkdir -p $(shell dirname $@)
	GOOS=$(shell dirname $@) go build -o $@ $<

linux/agbot: agbot/agbot.go perfutils/perfutils.go
	mkdir -p $(shell dirname $@)
	GOOS=$(shell dirname $@) go build -o $@ $<

$(GOOS)/smalltest: smalltest/smalltest.go
	@echo GOOS=$(GOOS)
	mkdir -p $(GOOS)
	go build -o $@ $<

testnode: $(GOOS)/node
	../bash/scale/deleteperforg.sh
	$< 1

testagbot: $(GOOS)/agbot
	../bash/scale/deleteperforg.sh
	$< 1
