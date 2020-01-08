// Performance test simulating many nodes making calls to the exchange. For scale testing, run many instances of this using wrapper.sh
package main

import (
	"fmt"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/open-horizon/exchange-api/src/test/go/perfutils"
)

func Usage(exitCode int) {
	fmt.Printf("Usage: %s <name base> [short-hostname]\n", perfutils.GetShortBinaryName())
	os.Exit(exitCode)
}

func main() {
	if len(os.Args) <= 1 {
		Usage(1)
	}

	scriptName := perfutils.GetShortBinaryName()
	namebase := os.Args[1] + "-node"
	var hostname = "" // this is for exchange resources that should only be created 1 per host
	if len(os.Args) >= 3 {
		hostname = os.Args[2]
	}

	rootauth := "root/root:" + perfutils.GetRequiredEnvVar("EXCHANGE_ROOTPW")
	EXCHANGE_IAM_KEY := perfutils.GetRequiredEnvVar("EXCHANGE_IAM_KEY")
	EXCHANGE_IAM_EMAIL := perfutils.GetRequiredEnvVar("EXCHANGE_IAM_EMAIL")
	HZN_EXCHANGE_URL := perfutils.GetRequiredEnvVar("HZN_EXCHANGE_URL")
	// Setting EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account) distinguishes this as an ibm public cloud environment, instead of ICP
	EXCHANGE_IAM_ACCOUNT_ID := os.Getenv("EXCHANGE_IAM_ACCOUNT_ID")

	// default of where to write the summary or error msgs. Can be overridden
	EX_PERF_REPORT_DIR := perfutils.GetEnvVarWithDefault("EX_PERF_REPORT_DIR", "/tmp/exchangePerf")
	reportDir := EX_PERF_REPORT_DIR + "/" + scriptName
	// this file holds the summary stats, and any errors that may have occurred along the way
	perfutils.EX_PERF_REPORT_FILE = perfutils.GetEnvVarWithDefault("EX_PERF_REPORT_FILE", reportDir+"/"+namebase+".summary")

	// The length of the performance test, measured in the number of times each node heartbeats (by default 60 sec each)
	numHeartbeats := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_HEARTBEATS", 15)
	// How many nodes this instance should simulate
	numNodes := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_NODES", 50)
	// EX_PERF_NUM_NODE_AGREEMENTS can be explicitly set to how many nodes should be given an agreement each hb interval, otherwise it will be calculated below. An estimate of the average number of msgs a node will have in flight at 1 time
	// How many msgs should be created for each node (to simulate agreement negotiation)
	//numMsgs := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_MSGS", 5)
	// create this many extra svcs so the nodes and patterns have to search thru them, but we will just use a primary/common svc for the pattern this group of nodes will use
	numSvcs := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_SVCS", 3)
	// create multiple patterns so the agbot has to serve them all, but we will just use the 1st one for this group of nodes
	numPatterns := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_PATTERNS", 1)
	// how much to sleep (if any) between creation and registration of each node
	createRegSleep := perfutils.GetEnvVarIntWithDefault("EX_PERF_CREATE_REG_SLEEP_MS", 0)

	// These defaults are taken from /etc/horizon/anax.json
	nodeHbInterval := perfutils.GetEnvVarIntWithDefault("EX_NODE_HB_INTERVAL", 60)
	svcCheckInterval := perfutils.GetEnvVarIntWithDefault("EX_NODE_SVC_CHECK_INTERVAL", 300)
	versionCheckInterval := perfutils.GetEnvVarIntWithDefault("EX_NODE_VERSION_CHECK_INTERVAL", 720)
	// EX_NODE_NO_SLEEP can be set to disable sleeping if it finishes an interval early

	// CURL_CA_BUNDLE can be exported in our parent if a self-signed cert is needed.

	// This script will create just 1 org and put everything else under that. If you use wrapper.sh, all instances of this script and agbot.go should use the same org.
	org := perfutils.GetEnvVarWithDefault("EX_PERF_ORG", "performancenodeagbot")

	// Determine whether we are using the public cloud or ICP
	var userauth string
	if EXCHANGE_IAM_ACCOUNT_ID != "" {
		userauth = org + "/iamapikey:" + EXCHANGE_IAM_KEY
	} else {
		// for ICP we can't play the game of associating our own org with another account, so we have to create/use a local exchange user
		userauth = org + "/" + EXCHANGE_IAM_EMAIL + ":" + EXCHANGE_IAM_KEY
	}

	nodebase := namebase + "-n"
	nodetoken := "abc123"

	nodeagrbase := namebase + "-node-agr" // agreement ids must be unique

	// this agbot id can not conflict with the agbots that agbot.go creates
	agbotbase := namebase + "-a"
	agbotid := agbotbase + "1"
	agbottoken := "abc123"
	//agbotauth := org + "/" + agbotid + ":" + agbottoken

	// svcurlbase is for creating the extra svcs. svcurl is the primary/common svc that all of the patterns will use
	var svcurlbase string
	if hostname != "" {
		svcurlbase = hostname + "-svcurl" // share 1 set of services for all instances on this host
	} else {
		svcurlbase = namebase + "-svcurl" // create our own services
	}
	// The svcurl value must match what agbot.go is using
	svcurl := "nodeagbotsvc"
	svcversion := "1.2.3"
	svcarch := "amd64"
	svcid := svcurl + "_" + svcversion + "_" + svcarch

	var patternbase string
	if hostname != "" {
		patternbase = hostname + "-p" // share 1 set of patterns for all instances on this host
	} else {
		patternbase = namebase + "-p" // create our own patterns
	}
	patternid := patternbase + "1"

	//buspolbase := namebase + "-bp"
	//buspolid := buspolbase + "1"

	var numNodeAgreements int
	if na := os.Getenv("EX_PERF_NUM_NODE_AGREEMENTS"); na != "" {
		numNodeAgreements = perfutils.Str2int(na)
	} else {
		// Calculate the num agreements per HB to finish all of the nodes a few HBs before the end
		numHB := numHeartbeats
		if numHB > 1 {
			numHB--
		}
		numNodeAgreements = (numNodes / numHB) + 1 // with integer division, the result is rounded down, so add 1
	}

	perfutils.ConfirmCmdsExist("curl", "jq")

	// =========== Initialization =================================================

	fmt.Printf("Initializing node test for %s, with %d heartbeats for %d nodes and %d agreements/HB:\n", namebase, numHeartbeats, numNodes, numNodeAgreements)
	fmt.Println("Using exchange " + HZN_EXCHANGE_URL)

	// Prepare the output dir
	perfutils.MakeDir(reportDir)
	perfutils.RemoveFile(perfutils.EX_PERF_REPORT_FILE)

	// Can not delete the org in case other instances of this script are using it. Whoever calls this script must delete it afterward.
	// So this is tolerant of the org already existing
	if EXCHANGE_IAM_ACCOUNT_ID != "" {
		// Using the public cloud
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org, rootauth, []int{403}, `{ "label": "perf test org", "description": "blah blah", "tags": { "ibmcloud_id": "`+EXCHANGE_IAM_ACCOUNT_ID+`" } }`, nil, false)
		// normally the exchange would automatically create this the 1st time it is used. But until issue 176 is fixed we need to explicitly create it. We'll get 400 if it was already created by another instance
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, rootauth, []int{400}, `{"password": "foobar", "admin": false, "email": "`+EXCHANGE_IAM_EMAIL+`"}`, nil, false)
		perfutils.ExchangeGet("orgs/"+org+"/users/iamapikey", userauth, nil, nil)
	} else {
		// Using ICP
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org, rootauth, []int{403}, `{ "label": "perf test org", "description": "blah blah" }`, nil, false)
		// for ICP we can't play the game of associating our own org with another account, so we have to create/use a local exchange user. We'll get 400 if it was already created by another instance
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, rootauth, []int{400}, `{"password": "`+EXCHANGE_IAM_KEY+`", "admin": false, "email": "`+EXCHANGE_IAM_EMAIL+`"}`, nil, false)
		perfutils.ExchangeGet("orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, userauth, nil, nil)
	}

	// Create the primary/common svc that all the patterns use. All instances of this driver use this, so be tolerant of it existing
	perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/services", userauth, []int{403}, `{"label": "svc", "public": true, "url": "`+svcurl+`", "version": "`+svcversion+`", "sharable": "singleton",
	  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "`+svcarch+`" }`, nil, false)

	// For the creation of services and patterns, we will share them with every other instance on this host if hostname is set, so need to tolerate them already existing
	var otherGoodHttpCodes []int
	if hostname != "" {
		otherGoodHttpCodes = []int{403}
	}

	// Create extra services
	for s := 1; s <= numSvcs; s++ {
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/services", userauth, otherGoodHttpCodes, `{"label": "svc", "public": true, "url": "`+svcurlbase+strconv.Itoa(s)+`", "version": "`+svcversion+`", "sharable": "singleton",
		  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "`+svcarch+`" }`, nil, true)
	}
	// Create patterns p*, that all use the primary service
	for p := 1; p <= numPatterns; p++ {
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/patterns/"+patternbase+strconv.Itoa(p), userauth, otherGoodHttpCodes, `{"label": "pat", "public": false, "services": [{ "serviceUrl": "`+svcurl+`", "serviceOrgid": "`+org+`", "serviceArch": "`+svcarch+`", "serviceVersions": [{ "version": "`+svcversion+`" }] }],
		"userInput": [{
			"serviceOrgid": "`+org+`", "serviceUrl": "`+svcurl+`", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
			"inputs": [{ "name": "VERBOSE", "value": true }]
		}] }`, nil, false)
	}

	// Create 1 agbot to be able to create node msgs
	perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/agbots/"+agbotid, userauth, []int{403}, `{"token": "`+agbottoken+`", "name": "agbot", "publicKey": "ABC"}`, nil, false)

	//todo: add policy objects and use them below

	// =========== Node Creation and Registration =================================================

	// start timing now
	perfutils.TotalOps = 0
	t1 := time.Now()

	for n := 1; n <= numNodes; n++ {
		mynodeid := nodebase + strconv.Itoa(n)
		mynodeauth := org + "/" + mynodeid + ":" + nodetoken

		if createRegSleep > 0 {
			time.Sleep(time.Duration(createRegSleep) * time.Millisecond)
		}

		// Make all the api calls a node makes during registration
		perfutils.ExchangeGet("admin/version", mynodeauth, nil, nil)
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+mynodeid, userauth, nil, `{"token": "`+nodetoken+`", "name": "pi", "pattern": "`+org+`/`+patternid+`", "arch": "`+svcarch+`", "publicKey": "ABC"}`, nil, false)
		perfutils.ExchangeGet("orgs/"+org+"/nodes/"+mynodeid, mynodeauth, nil, nil)
		perfutils.ExchangeGet("orgs/"+org, mynodeauth, nil, nil)
		perfutils.ExchangeGet("orgs/"+org+"/patterns/"+patternid, mynodeauth, nil, nil)
		perfutils.ExchangeP(http.MethodPatch, "orgs/"+org+"/nodes/"+mynodeid, mynodeauth, nil, `{ "registeredServices": [{"url": "`+org+`/`+svcurl+`", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "`+svcarch+`", "propType": "string", "op": "in"},{"name": "version", "value": "1.0.0", "propType": "version", "op": "in"}]}] }`, nil, true)
		perfutils.ExchangeGet("orgs/"+org+"/patterns/"+patternid, mynodeauth, nil, nil)
		perfutils.ExchangeGet("orgs/"+org+"/services", mynodeauth, nil, nil)
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+mynodeid+"/policy", mynodeauth, nil, `{ "properties": [{"name":"purpose", "value":"testing", "type":"string"}], "constraints":["a == b"] }`, nil, true)

		// Do not need to create msgs here to simulate agreement negotiation - agbot.go will do this when it finds the node in a search
		/* for m := 1; m <= numMsgs; m++ {
			perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/nodes/"+mynodeid+"/msgs", agbotauth, nil, `{"message": "hey there", "ttl": 8640000}`, nil, true) // ttl is 2400 hours - make sure they are there for the life of the test
		} */
	}

	// =========== Loop thru repeated exchange calls =================================================

	fmt.Printf("\nRunning %d heartbeats for %d nodes:\n", numHeartbeats, numNodes)
	svcCheckCount := 0
	versionCheckCount := 0
	nextNodeAgreement := 1
	var iterDeltaTotal time.Duration = 0
	var sleepTotal time.Duration = 0

	for h := 1; h <= numHeartbeats; h++ {
		fmt.Printf("Node heartbeat %d of %d for %d nodes\n", h, numHeartbeats, numNodes)
		startIteration := time.Now()
		// We assume 1 hb of all the nodes takes nodeHbInterval seconds, so increment our other counts by that much
		svcCheckCount += nodeHbInterval
		versionCheckCount += nodeHbInterval

		for n := 1; n <= numNodes; n++ {
			mynodeid := nodebase + strconv.Itoa(n)
			mynodeauth := org + "/" + mynodeid + ":" + nodetoken

			// These api methods are run every hb

			// temporarily put this here to see if put node was the cause of the body length 0 error (it wasn't)
			//perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+mynodeid, userauth, nil, `{"token": "`+nodetoken+`", "name": "pi", "pattern": "`+org+`/`+patternid+`", "arch": "`+svcarch+`", "publicKey": "ABC"}`, nil, true)

			perfutils.ExchangeGet("orgs/"+org+"/nodes/"+mynodeid, mynodeauth, nil, nil)
			perfutils.ExchangeGet("orgs/"+org+"/nodes/"+mynodeid+"/msgs", mynodeauth, []int{404}, nil)
			perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/nodes/"+mynodeid+"/heartbeat", mynodeauth, nil, nil, nil, true)
			perfutils.ExchangeGet("orgs/"+org+"/nodes/"+mynodeid+"/policy", mynodeauth, nil, nil)

			// If it is time to do a service check, do that
			if svcCheckCount >= svcCheckInterval {
				perfutils.ExchangeGet("orgs/"+org+"/services", mynodeauth, []int{404}, nil)
			}

			// If it is time to do a version check, do that
			if versionCheckCount >= versionCheckInterval {
				perfutils.ExchangeGet("admin/version", mynodeauth, nil, nil)
			}
		}

		// Give some (numNodeAgreements) nodes an agreement, so they won't be returned again in the agbot searches
		if nextNodeAgreement <= numNodes {
			toNodeAgreement := perfutils.MinInt(nextNodeAgreement+numNodeAgreements-1, numNodes)
			fmt.Printf("creating agreements for %s[%d - %d]", nodebase, nextNodeAgreement, toNodeAgreement) // was Debug()
			for n := nextNodeAgreement; n <= toNodeAgreement; n++ {
				mynodeid := nodebase + strconv.Itoa(n)
				mynodeauth := org + "/" + mynodeid + ":" + nodetoken
				agreementid := nodeagrbase + strconv.Itoa(n)
				perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+mynodeid+"/agreements/"+agreementid, mynodeauth, nil, `{"services": [], "agreementService": {"orgid": "`+org+`", "pattern": "`+org+`/`+patternid+`", "url": "`+org+`/`+svcurl+`"}, "state": "negotiating"}`, nil, true)
			}
			nextNodeAgreement += numNodeAgreements
		}

		// Reset our counters if appropriate
		if svcCheckCount >= svcCheckInterval {
			svcCheckCount = 0
		}
		if versionCheckCount >= versionCheckInterval {
			versionCheckCount = 0
		}

		// If we completed this iteration in less than nodeHbInterval, sleep the rest of the time (unless we are not supposed to)
		// Note: need to do all of the time calculations in Durations (int64 nanaseconds), and only convert to float64 seconds to display
		iterTime := time.Since(startIteration)
		iterDelta := perfutils.Seconds2Duration(nodeHbInterval) - iterTime
		iterDeltaTotal += iterDelta
		if iterDelta > 0 && os.Getenv("EX_NODE_NO_SLEEP") == "" {
			fmt.Printf("Sleeping for %f seconds at the end of node heartbeat %d of %d because loop iteration finished early\n", iterDelta.Seconds(), h, numHeartbeats)
			sleepTotal += iterDelta
			time.Sleep(iterDelta)
		}
	}

	// =========== Unregistration and Clean up ===========================================

	fmt.Println("\nUnregistering nodes and cleaning up from node test:")
	for n := 1; n <= numNodes; n++ {
		mynodeid := nodebase + strconv.Itoa(n)
		mynodeauth := org + "/" + mynodeid + ":" + nodetoken

		// Update node status when the services stop running
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+mynodeid+"/status", mynodeauth, nil, `{ "connectivity": {"firmware.bluehorizon.network": true}, "services": [] }`, nil, true)
	}

	// Don't need to delete the msgs, they'll get deleted with the node

	// We are sharing services and patterns with every other instance on this host if hostname is set, so need to tolerate them already being deleted
	otherGoodHttpCodes = nil // reset it
	if hostname != "" {
		otherGoodHttpCodes = []int{404}
	}

	// Delete patterns
	for p := 1; p <= numPatterns; p++ {
		mypatid := patternbase + strconv.Itoa(p)
		perfutils.ExchangeDelete("orgs/"+org+"/patterns/"+mypatid, userauth, otherGoodHttpCodes)
	}

	// Delete extra services
	for s := 1; s <= numSvcs; s++ {
		mysvcid := svcurlbase + strconv.Itoa(s) + "_" + svcversion + "_" + svcarch
		perfutils.ExchangeDelete("orgs/"+org+"/services/"+mysvcid, userauth, otherGoodHttpCodes)
	}

	// Delete primary service
	perfutils.ExchangeDelete("orgs/"+org+"/services/"+svcid, userauth, []int{404})

	// Delete nodes
	for n := 1; n <= numNodes; n++ {
		mynodeid := nodebase + strconv.Itoa(n)
		perfutils.ExchangeDelete("orgs/"+org+"/nodes/"+mynodeid, userauth, nil)
	}

	// Delete agbot
	perfutils.ExchangeDelete("orgs/"+org+"/agbots/"+agbotid, userauth, nil)

	// Can not delete the user or org in case other instances of this script are still using it. Whoever calls this script must delete it

	// Note: need to do all of the time calculations in Durations (int64 nanaseconds), and only convert to float64 seconds to display
	iterDeltaAvg := iterDeltaTotal / time.Duration(numHeartbeats)
	t2 := time.Now()
	tDelta := t2.Sub(t1) // this is a Duration
	activeTime := tDelta - sleepTotal
	activeTimeSecs := activeTime.Seconds() // this is float64
	opsAvg := activeTimeSecs / float64(perfutils.TotalOps)
	sumMsg := fmt.Sprintf("Simulated %d nodes for %d heartbeats\nStart time: %s, End time: %s, wall clock duration=%f s\nOverall: active time: %f s, num ops=%d, avg=%f s/op, avg iteration delta=%f s",
		numNodes, numHeartbeats, t1.Format("2006.01.02 15:04:05"), t2.Format("2006.01.02 15:04:05"), tDelta.Seconds(), activeTimeSecs, perfutils.TotalOps, opsAvg, iterDeltaAvg.Seconds())

	perfutils.Append2File(perfutils.EX_PERF_REPORT_FILE, sumMsg+"\n")
	fmt.Println("\n" + sumMsg)
}
