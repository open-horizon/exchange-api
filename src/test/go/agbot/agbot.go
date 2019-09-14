// Performance test simulating an agbot making calls to the exchange. For scale testing, run several instances of this using wrapper.sh
package main

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/open-horizon/exchange-api/src/test/go/perfutils"
)

func Usage(exitCode int) {
	fmt.Printf("Usage: %s [<name base>]\n", perfutils.GetShortBinaryName())
	os.Exit(exitCode)
}

// Response for getting the patterns from the exchange
type ExchangePatterns struct {
	Patterns map[string]interface{} `json:"patterns"`
}

// Response from the exchange for searching for nodes using a pattern
type PatternSearchNodes struct {
	Id string `json:"id"`
}
type ExchangePatternSearch struct {
	Nodes []PatternSearchNodes `json:"nodes"`
}

func main() {
	if len(os.Args) <= 1 {
		Usage(1)
	}

	scriptName := filepath.Base(os.Args[0])
	namebase := os.Args[1] + "-agbot"

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

	// The length of the performance test, measured in the number of times each agbot checks for agreements (by default 10 sec each)
	numAgrChecks := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_AGR_CHECKS", 90)
	// How many agbots this instance should simulate
	numAgbots := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_AGBOTS", 1)
	// How many msgs should be created for each agbot (to simulate agreement negotiation)
	numMsgs := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_MSGS", 50)

	/* These defaults are taken from /etc/horizon/anax.json
	"NewContractIntervalS": 10, (gets patterns and policies, and do both /search apis)
	"ProcessGovernanceIntervalS": 10, (decide if existing agreements should continue, nodehealth, etc.)
	"ExchangeVersionCheckIntervalM": 1, (60 sec)
	"ExchangeHeartbeat": 60,
	"ActiveDeviceTimeoutS": 180, <- maybe not relevant
	AgreementBot.CheckUpdatedPolicyS: 15 (check for updated policies)
	AgreementTimeoutS
	*/
	newAgreementInterval := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_NEW_AGR_INTERVAL", 10)
	// Note: the default value of newAgreementInterval and processGovInterval are the same, so for now we assume they are the same value
	//processGovInterval := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_PROC_GOV_INTERVAL", 10)
	agbotHbInterval := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_HB_INTERVAL", 60)
	versionCheckInterval := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_VERSION_CHECK_INTERVAL", 60)

	shortCircuitChkInterval := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_SHORT_CIRCUIT_CHK_INTERVAL", 10)
	requiredEmptyIntervals := perfutils.GetEnvVarIntWithDefault("EX_AGBOT_SHORT_CIRCUIT_EMPTY_INTERVALS", 3)
	// EX_AGBOT_NO_SLEEP can be set to disable sleeping if it finishes an interval early
	// EX_AGBOT_CREATE_PATTERN can be set to have this script create 1 pattern, so it finds something even if node.go is not running

	// CURL_CA_BUNDLE can be exported in our parent if a self-signed cert is needed.

	// This script will create just 1 org and put everything else under that. If you use wrapper.sh, all instances of this script and agbot.sh should use the same org.
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
	nodeid := nodebase + "1"
	nodetoken := "abc123"
	nodeauth := org + "/" + nodeid + ":" + nodetoken

	// this agbot id can not conflict with the agbots that agbot.go creates
	agbotbase := namebase + "-a"
	agbottoken := "abcdef"

	// The svcurl value must match what node.go is using
	svcurl := "nodeagbotsvc"
	svcversion := "1.2.3"
	svcarch := "amd64"
	svcid := svcurl + "_" + svcversion + "_" + svcarch

	patternbase := namebase + "-p"
	patternid := patternbase + "1"

	//buspolbase := namebase + "-bp"
	//buspolid := buspolbase + "1"

	perfutils.ConfirmCmdsExist("curl", "jq")

	// =========== Initialization =================================================

	fmt.Printf("Initializing agbot test for %s, with %d agreement checks:\n", namebase, numAgrChecks)
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
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, rootauth, []int{400}, `{"password": "foobar", "admin": false, "email": "`+EXCHANGE_IAM_EMAIL+`"}`, nil, false)
		perfutils.ExchangeGet("orgs/"+org+"/users/iamapikey", userauth, nil, nil)
	} else {
		// Using ICP
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org, rootauth, []int{403}, `{ "label": "perf test org", "description": "blah blah" }`, nil, false)
		// for ICP we can't play the game of associating our own org with another account, so we have to create/use a local exchange user. We'll get 400 if it was already created by another instance
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, rootauth, []int{400}, `{"password": "`+EXCHANGE_IAM_KEY+`", "admin": false, "email": "`+EXCHANGE_IAM_EMAIL+`"}`, nil, false)
		perfutils.ExchangeGet("orgs/"+org+"/users/"+EXCHANGE_IAM_EMAIL, userauth, nil, nil)
	}

	// let node.go create most of the services, patterns, and business policies

	// Create the agbots and configure them to watch the patterns
	for a := 1; a <= numAgbots; a++ {
		myagbotid := agbotbase + strconv.Itoa(a)
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/agbots/"+myagbotid, userauth, nil, `{"token": "`+agbottoken+`", "name": "agbot", "publicKey": "ABC"}`, nil, false)

		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/agbots/"+myagbotid+"/patterns", userauth, nil, `{"patternOrgid": "`+org+`", "pattern": "*", "nodeOrgid": "`+org+`"}`, nil, false)
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/agbots/"+myagbotid+"/patterns", userauth, nil, `{"patternOrgid": "IBM", "pattern": "*", "nodeOrgid": "`+org+`"}`, nil, false)
	}

	if os.Getenv("EX_AGBOT_CREATE_PATTERN") != "" {
		// Create 1 svc, pattern, and node to be able to create agbot msgs, and to have pattern search return at least 1 node
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/services", userauth, []int{403}, `{"label": "svc", "public": true, "url": "`+svcurl+`", "version": "`+svcversion+`", "sharable": "singleton",
		  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "`+svcarch+`" }`, nil, false)
		perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/patterns/"+patternid, userauth, nil, `{"label": "pat", "public": false, "services": [{ "serviceUrl": "`+svcurl+`", "serviceOrgid": "`+org+`", "serviceArch": "`+svcarch+`", "serviceVersions": [{ "version": "`+svcversion+`" }] }] }`, nil, false)
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+nodeid, userauth, nil, `{"token": "`+nodetoken+`", "name": "pi", "pattern": "`+org+`/`+patternid+`", "arch": "`+svcarch+`", "publicKey": "ABC"}`, nil, false)
	} else {
		// Create 1 node to be able to create agbot msgs
		perfutils.ExchangeP(http.MethodPut, "orgs/"+org+"/nodes/"+nodeid, userauth, nil, `{"token": "`+nodetoken+`", "name": "pi", "pattern": "", "arch": "`+svcarch+`", "publicKey": "ABC"}`, nil, false)
	}

	// Create agbot msgs
	for a := 1; a <= numAgbots; a++ {
		myagbotid := agbotbase + strconv.Itoa(a)
		for i := 1; i <= numMsgs; i++ {
			perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/agbots/"+myagbotid+"/msgs", nodeauth, nil, `{"message": "hey there", "ttl": 8640000}`, nil, true) // ttl is 2400 hours - make sure they are there for the life of the test
		}
	}
	//todo: add policy objects and use them below

	// =========== Loop thru repeated exchange calls =================================================

	// start timing now
	perfutils.TotalOps = 0
	t1 := time.Now()

	fmt.Printf("\nRunning %d agreement checks for %d agbots:\n", numAgrChecks, numAgbots)
	agbotHbCount := 0
	versionCheckCount := 0
	patsMaxProcessed := 0
	nodesProcessed := 0
	nodesMinProcessed := 100000
	nodesMaxProcessed := 0
	nodesLastProcessed := 0
	var iterDeltaTotal time.Duration = 0
	var sleepTotal time.Duration = 0
	shortCircuit := false // detected that all node.go instances don't have any nodes w/o agreements any more, so we should stop sleeping
	emptyIntervals := 0   // how many intervals we have had with no patterns found

	for h := 1; h <= numAgrChecks; h++ {
		fmt.Printf("Agbot agreement check %d of %d\n", h, numAgrChecks)
		startIteration := time.Now()
		// We assume 1 agreement check of all the agbots takes newAgreementInterval seconds, so increment our other counts by that much
		agbotHbCount += newAgreementInterval
		versionCheckCount += newAgreementInterval

		for a := 1; a <= numAgbots; a++ {
			myagbotid := agbotbase + strconv.Itoa(a)
			myagbotauth := org + "/" + myagbotid + ":" + agbottoken

			// we don't actually use this info, but the agbots query it, so we should
			perfutils.ExchangeGet("orgs/"+org+"/agbots/"+myagbotid+"/patterns", myagbotauth, nil, nil)
			perfutils.ExchangeGet("orgs/"+org, myagbotauth, nil, nil)
			perfutils.ExchangeGet("orgs/IBM", rootauth, nil, nil)

			// These api methods are run every agreement check and process governance:
			// Get the patterns in the org and do a search for each one. Note: we are only getting the patterns in our org, because the number of patterns in the IBM org will be small in comparison.
			url := "orgs/" + org + "/patterns"
			perfutils.Verbose("Running GET (%s) %s", myagbotauth, url)
			var patResp ExchangePatterns
			httpCode := perfutils.ExchangeGet(url, myagbotauth, []int{404}, &patResp)
			if httpCode == 200 || httpCode == 404 { // even with 404 we get a valid response structure
				//perfutils.Debug("patterns: %v", patResp)
				numPatterns := len(patResp.Patterns)
				fmt.Printf("Agbot %d processing %d patterns\n", a, numPatterns)
				if numPatterns == 0 {
					emptyIntervals++
				} else {
					emptyIntervals = 0 // reset it
				}
				patsMaxProcessed = perfutils.MaxInt(patsMaxProcessed, numPatterns)
				numAgrChkNodes := 0
				// Loop thru the patterns this agbot is serving
				for p := range patResp.Patterns {
					pat := perfutils.TrimOrg(p) // the pattern ids are returned to us with the org prepended

					// run nodehealth for this pattern. Not sure yet what to do with this result yet
					perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/patterns/"+pat+"/nodehealth", myagbotauth, []int{404}, `{ "lastTime": "" }`, nil, true) // empty string for lastTime will return all nodes
					perfutils.ExchangeGet("orgs/"+org+"/services", myagbotauth, []int{404}, nil)
					perfutils.ExchangeGet("orgs/"+org+"/services/"+svcid, myagbotauth, []int{404}, nil) // not sure why both of these are called, but they are

					// Search for nodes with this pattern
					url := "orgs/" + org + "/patterns/" + pat + "/search"
					perfutils.Verbose("Running POST (%s) %s", myagbotauth, url)
					var nodeResp ExchangePatternSearch
					httpCode := perfutils.ExchangeP(http.MethodPost, url, myagbotauth, []int{404, 400}, `{ "serviceUrl": "`+org+`/`+svcurl+`", "secondsStale": 0, "startIndex": 0, "numEntries": 0 }`, &nodeResp, true)
					if httpCode == 201 || httpCode == 404 { // even with 404 we get a valid response structure
						//perfutils.Debug("pattern search: %v", nodeResp)
						numNodes := len(nodeResp.Nodes)
						perfutils.Debug("pattern %s search found %d nodes", pat, numNodes)
						nodesProcessed += numNodes
						numAgrChkNodes += numNodes
						nodesMaxProcessed = perfutils.MaxInt(nodesMaxProcessed, numNodes)
						nodesMinProcessed = perfutils.MinInt(nodesMinProcessed, numNodes)
						nodesLastProcessed = numNodes
						// Loop thru the nodes that are candidates to make agreement with for this pattern
						for _, n := range nodeResp.Nodes {
							nid := perfutils.TrimOrg(n.Id) // the node ids are returned to us with the org prepended
							perfutils.Verbose("Node %s", nid)

							// Simulate agreement negotiation by posting some short-lived msgs to the node
							// the acceptable 404 http code below handle the case in which the node was deleted between the time of the search and now
							perfutils.ExchangeGet("orgs/"+org+"/nodes/"+nid, myagbotauth, []int{404}, nil)
							for i := 1; i <= 2; i++ {
								perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/nodes/"+nid+"/msgs", myagbotauth, []int{404}, `{"message": "hey there", "ttl": 5}`, nil, true)
							}
							// we query our own msgs below, so don't have to do that here
						}
					}
				} // end of for patterns
				//todo: query agbot businesspols orgs and business policies and do a search for each, and query service policy
				fmt.Printf("Agbot %d processed %d nodes\n", a, numAgrChkNodes)
			} // end of 200 or 404 from GET patterns

			// Get my agbot msgs
			perfutils.ExchangeGet("orgs/"+org+"/agbots/"+myagbotid+"/msgs", myagbotauth, nil, nil)

			// If it is time to heartbeat, do that
			if agbotHbCount >= agbotHbInterval {
				perfutils.ExchangeP(http.MethodPost, "orgs/"+org+"/agbots/"+myagbotid+"/heartbeat", myagbotauth, nil, nil, nil, true)
				perfutils.ExchangeGet("orgs/"+org+"/agbots/"+myagbotid, myagbotauth, nil, nil)
			}

			// If it is time to do a version check, do that
			if versionCheckCount >= versionCheckInterval {
				perfutils.ExchangeGet("admin/version", myagbotauth, nil, nil)
			}
		}

		// Reset our counters if appropriate
		if agbotHbCount >= agbotHbInterval {
			agbotHbCount = 0
		}
		if versionCheckCount >= versionCheckInterval {
			versionCheckCount = 0
		}

		// If we completed this iteration in less than newAgreementInterval, sleep the rest of the time (unless we are not supposed to)
		// Note: need to do all of the time calculations in Durations (int64 nanaseconds), and only convert to float64 seconds to display
		iterTime := time.Since(startIteration)
		iterDelta := perfutils.Seconds2Duration(newAgreementInterval) - iterTime
		iterDeltaTotal += iterDelta
		if !shortCircuit && h >= shortCircuitChkInterval && emptyIntervals >= requiredEmptyIntervals {
			// stop sleeping if we have done more than 10 intervals and the last 3 intervals have had 0 patterns
			shortCircuit = true
		}
		if iterDelta > 0 && os.Getenv("EX_AGBOT_NO_SLEEP") == "" && !shortCircuit {
			fmt.Printf("Sleeping for %f seconds at the end of agbot agreement check %d of %d because loop iteration finished early\n", iterDelta.Seconds(), h, numAgrChecks)
			sleepTotal += iterDelta
			time.Sleep(iterDelta)
		}
	}

	// =========== Clean up ===========================================

	fmt.Println("\nCleaning up from agbot test:")

	// Don't need to delete the msgs, they'll get deleted with the agbot

	// Delete agbot
	for a := 1; a <= numAgbots; a++ {
		myagbotid := agbotbase + strconv.Itoa(a)
		perfutils.ExchangeDelete("orgs/"+org+"/agbots/"+myagbotid, userauth, nil)
	}

	if os.Getenv("EX_AGBOT_CREATE_PATTERN") != "" {
		// Delete the pattern and service
		perfutils.ExchangeDelete("orgs/"+org+"/patterns/"+patternid, userauth, nil)
		perfutils.ExchangeDelete("orgs/"+org+"/services/"+svcid, userauth, []int{404})
	}

	// Delete the node
	perfutils.ExchangeDelete("orgs/"+org+"/nodes/"+nodeid, userauth, nil)

	// Can not delete the org in case other instances of this script are still using it. Whoever calls this script must delete it

	// Note: need to do all of the time calculations in Durations (int64 nanaseconds), and only convert to float64 seconds to display
	iterDeltaAvg := iterDeltaTotal / time.Duration(numAgrChecks)
	nodesProcAvg := float64(nodesProcessed) / float64(numAgrChecks)
	t2 := time.Now()
	tDelta := t2.Sub(t1) // this is a Duration
	activeTime := tDelta - sleepTotal
	activeTimeSecs := activeTime.Seconds() // this is float64
	opsAvg := activeTimeSecs / float64(perfutils.TotalOps)

	sumMsg := fmt.Sprintf("Simulated %d agbots for %d agreement-checks\nMax patterns=%d, total nodes=%d, avg=%f nodes/agr-chk\nMax nodes=%d, min nodes=%d, last nodes=%d\nStart time: %s, End time: %s, wall clock duration=%f s\nOverall: active time: %f s, num ops=%d, avg=%f s/op, avg iteration delta=%f s",
		numAgbots, numAgrChecks, patsMaxProcessed, nodesProcessed, nodesProcAvg, nodesMaxProcessed, nodesMinProcessed, nodesLastProcessed, t1.Format("2006.01.02 15:04:05"), t2.Format("2006.01.02 15:04:05"), tDelta.Seconds(), activeTimeSecs, perfutils.TotalOps, opsAvg, iterDeltaAvg.Seconds())

	perfutils.Append2File(perfutils.EX_PERF_REPORT_FILE, sumMsg+"\n")
	fmt.Println("\n" + sumMsg)
}
