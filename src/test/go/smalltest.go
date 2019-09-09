// Very quick performance test of the exchange
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/open-horizon/exchange-api/src/test/go/perfutils"
)

func Usage(exitCode int) {
	fmt.Printf("Usage: %s num-times\n", os.Args[0])
	os.Exit(exitCode)
}

func main() {
	if len(os.Args) <= 1 {
		Usage(1)
	}

	numTimes, err := strconv.Atoi(os.Args[1])
	if err != nil {
		perfutils.Fatal(2, "first arg num-times must be an integer number")
	}

	org := "IBM"
	nodeid := "n1"
	//userauth := org + "/" + perfutils.GetRequiredEnvVar("HZN_EXCHANGE_USER_AUTH")
	nodeauth := org + "/" + perfutils.GetRequiredEnvVar("HZN_EXCHANGE_NODE_AUTH")
	//agbotauth := org + "/" + perfutils.GetRequiredEnvVar("EXCHANGE_AGBOTAUTH")

	httpClient := perfutils.GetHTTPClient()

	fmt.Println("Starting smalltest...")
	t1 := time.Now()

	for i := 1; i <= numTimes; i++ {
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
	numOps := numTimes
	opsAvg := tDelta.Seconds() / float64(numOps)
	fmt.Printf("\nTotal time: %f s, num ops=%d, avg=%f s/op\n", tDelta.Seconds(), numOps, opsAvg)
}
