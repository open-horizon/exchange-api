// Utility functions for the perf/scale drivers
package perfutils

import (
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	retryMax                   = 5
	retrySleep                 = 2
	HTTPRequestTimeoutS        = 30
	MaxHTTPIdleConnections     = 20
	HTTPIdleConnectionTimeoutS = 120
)

func GetRequiredEnvVar(envVarName string) string {
	envVarValue := os.Getenv(envVarName)
	if envVarValue == "" {
		Fatal(2, "environment variable %s is required", envVarName)
	}
	return envVarValue
}

func GetEnvVarWithDefault(envVarName, defaultValue string) string {
	envVarValue := os.Getenv(envVarName)
	if envVarValue == "" {
		return defaultValue
	}
	return envVarValue
}

func GetEnvVarIntWithDefault(envVarName string, defaultValue int) int {
	envVarValue := os.Getenv(envVarName)
	if envVarValue == "" {
		return defaultValue
	}
	return Str2int(envVarValue)
}

func IsVerbose() bool {
	return GetEnvVarWithDefault("VERBOSE", "false") == "true"
}

func Verbose(msg string, args ...interface{}) {
	if !IsVerbose() {
		return
	}
	if !strings.HasSuffix(msg, "\n") {
		msg += "\n"
	}
	fmt.Printf("Error: "+msg, args...)
}

func Fatal(exitCode int, msg string, args ...interface{}) {
	if !strings.HasSuffix(msg, "\n") {
		msg += "\n"
	}
	fmt.Fprintf(os.Stderr, "Error: "+msg, args...)
	os.Exit(exitCode)
}

func Str2int(str string) int {
	i, err := strconv.Atoi(str)
	if err != nil {
		Fatal(2, "could not convert "+str+" to an integer number")
	}
	return i
}

// Unmarshal simply calls json.Unmarshal and handles any errors
func Unmarshal(data []byte, v interface{}, errMsg string) {
	err := json.Unmarshal(data, v)
	if err != nil {
		Fatal(5, "failed to unmarshal bytes from %s: %v", errMsg, err)
	}
}

// MarshalIndent calls json.MarshalIndent and handles any errors
func MarshalIndent(v interface{}, errMsg string) string {
	jsonBytes, err := json.MarshalIndent(v, "", "    ")
	if err != nil {
		Fatal(5, "failed to marshal data type from %s: %v", errMsg, err)
	}
	return string(jsonBytes)
}

// Add the given org to the id if the id does not already contain an org
func AddOrg(id string) string {
	substrings := strings.Split(id, "/")
	if len(substrings) <= 1 { // this means id was empty, or did not contain '/'
		return fmt.Sprintf("%v/%v", GetRequiredEnvVar("HZN_ORG_ID"), id)
	} else if len(substrings) == 2 {
		return id
	} else {
		Fatal(2, "the id can not contain more than 1 '/'")
	}
	return "" // will never get here
}

func GetExchangeUrl() string {
	return GetRequiredEnvVar("HZN_EXCHANGE_URL")
}

// Common function for getting an HTTP client connection object.
func GetHTTPClient() *http.Client {

	// This env var should only be used in our test environments or in an emergency when there is a problem with the SSL certificate of a horizon service.
	skipSSL := false
	if os.Getenv("HZN_SSL_SKIP_VERIFY") != "" {
		skipSSL = true
	}

	return &http.Client{
		// remember that this timeout is for the whole request, including
		// body reading. This means that you must set the timeout according
		// to the total payload size you expect
		Timeout: time.Second * time.Duration(HTTPRequestTimeoutS),
		Transport: &http.Transport{
			Dial: (&net.Dialer{
				Timeout:   20 * time.Second,
				KeepAlive: 60 * time.Second,
			}).Dial,
			TLSHandshakeTimeout:   20 * time.Second,
			ResponseHeaderTimeout: 20 * time.Second,
			ExpectContinueTimeout: 8 * time.Second,
			MaxIdleConns:          MaxHTTPIdleConnections,
			IdleConnTimeout:       HTTPIdleConnectionTimeoutS * time.Second,
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: skipSSL,
			},
		},
	}

}

func IsTransportError(err error) bool {
	l_error_string := strings.ToLower(err.Error())
	if strings.Contains(l_error_string, "time") && strings.Contains(l_error_string, "out") {
		return true
	} else if strings.Contains(l_error_string, "connection") && (strings.Contains(l_error_string, "refused") || strings.Contains(l_error_string, "reset")) {
		return true
	}
	return false
}

func invokeRestApiWithRetry(httpClient *http.Client, req *http.Request) *http.Response {
	retryCount := 0
	for {
		retryCount++
		if resp, err := httpClient.Do(req); err != nil {
			if IsTransportError(err) {
				if retryCount <= retryMax {
					Verbose("error calling %s %s: %v. Will retry...", req.Method, req.URL, err)
					// retry for network tranport errors
					time.Sleep(retrySleep * time.Second)
					continue
				} else {
					Fatal(6, "error calling %s %s: %v. At retry max, exiting.", req.Method, req.URL, err)
				}
			} else {
				Fatal(6, "error calling %s %s: %v", req.Method, req.URL, err)
			}
		} else {
			return resp
		}
	}
}

func isGoodCode(actualHttpCode int, goodHttpCodes []int) bool {
	if len(goodHttpCodes) == 0 {
		return true // passing in an empty list of good codes means anything is ok
	}
	for _, code := range goodHttpCodes {
		if code == actualHttpCode {
			return true
		}
	}
	return false
}

func ExchangeGet(httpClient *http.Client, urlSuffix, credentials string, goodHttpCodes []int, structure interface{}) (httpCode int) {
	url := GetExchangeUrl() + "/" + urlSuffix
	apiMsg := http.MethodGet + " " + url

	Verbose(apiMsg)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		Fatal(3, "%s new request failed: %v", apiMsg, err)
	}
	req.Header.Add("Accept", "application/json")
	if credentials != "" {
		req.Header.Add("Authorization", fmt.Sprintf("Basic %v", base64.StdEncoding.EncodeToString([]byte(credentials))))
	}

	resp := invokeRestApiWithRetry(httpClient, req)
	defer resp.Body.Close()
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		Fatal(3, "failed to read body response from %s: %v", apiMsg, err)
	}
	httpCode = resp.StatusCode
	Verbose("HTTP code: %d", httpCode)
	if !isGoodCode(httpCode, goodHttpCodes) {
		Fatal(3, "bad HTTP code %d from %s, output: %s", httpCode, apiMsg, string(bodyBytes))
	}

	if len(bodyBytes) > 0 && structure != nil { // some front-ends of exchange will return nothing when auth problem
		switch s := structure.(type) {
		case *[]byte:
			// This is the signal that they want the raw body back
			*s = bodyBytes
		case *string:
			// If the structure to fill in is just a string, unmarshal/remarshal it to get it in json indented form, and then return as a string
			//todo: this gets it in json indented form, but also returns the fields in random order (because they were interpreted as a map)
			var jsonStruct interface{}
			err = json.Unmarshal(bodyBytes, &jsonStruct)
			if err != nil {
				Fatal(4, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
			jsonBytes, err := json.MarshalIndent(jsonStruct, "", "    ")
			if err != nil {
				Fatal(4, "failed to marshal exchange output from %s: %v", apiMsg, err)
			}
			*s = string(jsonBytes)
		default:
			err = json.Unmarshal(bodyBytes, structure)
			if err != nil {
				Fatal(4, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
		}
	}
	return
}
