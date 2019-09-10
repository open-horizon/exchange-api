// Performance test simulating many nodes making calls to the exchange. For scale testing, run many instances of this using wrapper.sh
package main

import (
	"fmt"
	"os"
	"path"
	"time"

	"github.com/open-horizon/exchange-api/src/test/go/perfutils"
)

func Usage(exitCode int) {
	fmt.Printf("Usage: %s [<name base>]\n", os.Args[0])
	os.Exit(exitCode)
}

func main() {
	if len(os.Args) <= 1 {
		Usage(1)
	}

	scriptName := path.Base(os.Args[0])
	namebase := os.Args[1]

	rootauth := "root/root:" + perfutils.GetRequiredEnvVar("EXCHANGE_ROOTPW")
	EXCHANGE_IAM_KEY := perfutils.GetRequiredEnvVar("EXCHANGE_IAM_KEY")
	EXCHANGE_IAM_EMAIL := perfutils.GetRequiredEnvVar("EXCHANGE_IAM_EMAIL")
	HZN_EXCHANGE_URL := perfutils.GetRequiredEnvVar("HZN_EXCHANGE_URL")
	// Setting EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account) distinguishes this as an ibm public cloud environment, instead of ICP

	EX_PERF_ORG := perfutils.GetEnvVarWithDefault("EX_PERF_ORG", "performancenodeagbot")

	// default of where to write the summary or error msgs. Can be overridden
	EX_PERF_REPORT_DIR := perfutils.GetEnvVarWithDefault("EX_PERF_REPORT_DIR", "/tmp/exchangePerf")
	reportDir := EX_PERF_REPORT_DIR + "/" + scriptName
	// this file holds the summary stats, and any errors that may have occurred along the way
	EX_PERF_REPORT_FILE := perfutils.GetEnvVarWithDefault("EX_PERF_REPORT_FILE", reportDir+"/"+namebase+".summary")
	// this file holds the output of the most recent curl cmd, which is useful when the curl cmd errors out
	EX_PERF_DEBUG_FILE := perfutils.GetEnvVarWithDefault("EX_PERF_DEBUG_FILE", reportDir+"/debug/"+namebase+".lastmsg")

	// The length of the performance test, measured in the number of times each node heartbeats (by default 60 sec each)
	numHeartbeats := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_HEARTBEATS", 15)
	// How many nodes this instance should simulate
	numNodes := perfutils.GetEnvVarIntWithDefault("EX_PERF_NUM_NODES", 50)

	org := "IBM"
	nodeid := "n1"
	//userauth := org + "/" + perfutils.GetRequiredEnvVar("HZN_EXCHANGE_USER_AUTH")
	nodeauth := org + "/" + perfutils.GetRequiredEnvVar("HZN_EXCHANGE_NODE_AUTH")
	//agbotauth := org + "/" + perfutils.GetRequiredEnvVar("EXCHANGE_AGBOTAUTH")

	httpClient := perfutils.GetHTTPClient()

	fmt.Println("Starting smalltest...")
	t1 := time.Now()

	for i := 1; i <= numHeartbeats; i++ {
		var resp []byte
		httpCode := perfutils.ExchangeGet(httpClient, "orgs/"+org+"/nodes/"+nodeid, perfutils.AddOrg(nodeauth), []int{200}, &resp)
		if perfutils.IsVerbose() {
			fmt.Printf("httpCode=%d\n", httpCode)
		} else {
			fmt.Printf(".")
		}
	}

	t2 := time.Now()
	tDelta := t2.Sub(t1)
	numOps := numHeartbeats
	opsAvg := tDelta.Seconds() / float64(numOps)
	fmt.Printf("\nTotal time: %f s, num ops=%d, avg=%f s/op\n", tDelta.Seconds(), numOps, opsAvg)
}
