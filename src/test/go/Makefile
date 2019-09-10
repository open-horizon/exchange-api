# Build the performance/scale test drivers for the exchange

# To cross-compile for linux on mac: GOOS=linux make smalltest
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
	go build -o $(GOOS)/$@ $<

$(GOOS)/node: node/node.go
	@echo GOOS=$(GOOS)
	mkdir -p $(GOOS)
	go build -o $(GOOS)/$@ $<

run: $(GOOS)/smalltest
	$<
