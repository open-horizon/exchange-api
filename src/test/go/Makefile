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

$(GOOS)/smalltest: smalltest/smalltest.go
	@echo GOOS=$(GOOS)
	mkdir -p $(GOOS)
	go build -o $@ $<

$(GOOS)/node: node/node.go perfutils/perfutils.go
	mkdir -p $(GOOS)
	go build -o $@ $<

$(GOOS)/agbot: agbot/agbot.go perfutils/perfutils.go
	mkdir -p $(GOOS)
	go build -o $@ $<

testnode: $(GOOS)/node
	../bash/scale/deleteperforg.sh
	$< 1

testagbot: $(GOOS)/agbot
	../bash/scale/deleteperforg.sh
	$< 1
