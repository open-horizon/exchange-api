// Utility functions for the perf/scale drivers
package perfutils

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"math"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
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

	// Exit Codes
	CLI_INPUT_ERROR    = 1
	JSON_PARSING_ERROR = 3
	FILE_IO_ERROR      = 4
	HTTP_ERROR         = 5
	CLI_GENERAL_ERROR  = 7
	NOT_FOUND          = 8
	EXEC_CMD_ERROR     = 10
	INTERNAL_ERROR     = 99
)

var EX_PERF_REPORT_FILE string
var HttpClient *http.Client // holds the global client we reuse
var TotalOps int            // holds the total number of rest apis we have run

func GetRequiredEnvVar(envVarName string) string {
	envVarValue := os.Getenv(envVarName)
	if envVarValue == "" {
		Fatal(CLI_INPUT_ERROR, "environment variable %s is required", envVarName)
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

func GetShortBinaryName() string {
	return filepath.Base(os.Args[0])
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
	fmt.Printf("Verbose: "+msg, args...)
}

func Debug(msg string, args ...interface{}) {
	//if !IsVerbose() { return }
	if !strings.HasSuffix(msg, "\n") {
		msg += "\n"
	}
	fmt.Printf("DEBUG "+GetShortBinaryName()+": "+msg, args...)
}

func Error(msg string, args ...interface{}) {
	if !strings.HasSuffix(msg, "\n") {
		msg += "\n"
	}
	errMsg := fmt.Sprintf("Error:==> "+time.Now().String()+" "+msg, args...)
	// write error msg to both the summary file and stderr
	Append2File(EX_PERF_REPORT_FILE, errMsg)
	fmt.Fprint(os.Stderr, errMsg)
}

func Fatal(exitCode int, msg string, args ...interface{}) {
	Error(msg, args...)
	os.Exit(exitCode)
}

func MinInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func MaxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

// RoundInt returns the nearest int result. To get more exact, look at https://github.com/a-h/round
func Round2Int(f float64) int {
	if f < 0 {
		return int(math.Ceil(f - 0.5))
	}
	return int(math.Floor(f + 0.5))
}

func Str2int(str string) int {
	i, err := strconv.Atoi(str)
	if err != nil {
		Fatal(CLI_INPUT_ERROR, "could not convert "+str+" to an integer number")
	}
	return i
}

func Seconds2Duration(seconds int) time.Duration {
	return time.Duration(seconds) * time.Second
}

// To convert from Duration to seconds, use duration.Seconds()

// Unmarshal simply calls json.Unmarshal and handles any errors
func Unmarshal(data []byte, v interface{}, errMsg string) {
	err := json.Unmarshal(data, v)
	if err != nil {
		Fatal(JSON_PARSING_ERROR, "failed to unmarshal bytes from %s: %v", errMsg, err)
	}
}

// MarshalIndent calls json.MarshalIndent and handles any errors
func MarshalIndent(v interface{}, errMsg string) string {
	jsonBytes, err := json.MarshalIndent(v, "", "    ")
	if err != nil {
		Fatal(JSON_PARSING_ERROR, "failed to marshal data type from %s: %v", errMsg, err)
	}
	return string(jsonBytes)
}

// TrimOrg returns id with the leading "<org>/" removed, if it was there.
func TrimOrg(id string) string {
	substrings := strings.Split(id, "/")
	if len(substrings) <= 1 { // this means id was empty, or did not contain '/'
		return id
	} else if len(substrings) == 2 {
		return substrings[1]
	} else {
		Fatal(CLI_INPUT_ERROR, "can not remove or from id '%s' because it contains more than 1 '/'", id)
	}
	return "" // will never get here
}

// Add the given org to the id if the id does not already contain an org
func AddOrg(id string) string {
	substrings := strings.Split(id, "/")
	if len(substrings) <= 1 { // this means id was empty, or did not contain '/'
		return fmt.Sprintf("%v/%v", GetRequiredEnvVar("HZN_ORG_ID"), id)
	} else if len(substrings) == 2 {
		return id
	} else {
		Fatal(CLI_INPUT_ERROR, "the id can not contain more than 1 '/'")
	}
	return "" // will never get here
}

func Append2File(path, text string) {
	// If the file doesn't exist, create it, or append to the file
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		Fatal(FILE_IO_ERROR, "could not open %s: %v", path, err)
	}
	defer f.Close()
	if _, err = f.WriteString(text); err != nil {
		Fatal(FILE_IO_ERROR, "could not write to %s: %v", path, err)
	}
}

func GetExchangeUrl() string {
	return GetRequiredEnvVar("HZN_EXCHANGE_URL")
}

// Common function for getting an HTTP client connection object.
func GetHTTPClient() *http.Client {
	if os.Getenv("EX_PERF_DONT_REUSE_HTTP_CLIENT") == "" && HttpClient != nil {
		return HttpClient
	} // reuse the global instance

	// This env var should only be used in our test environments or in an emergency when there is a problem with the SSL certificate of a horizon service.
	skipSSL := false
	if os.Getenv("HZN_SSL_SKIP_VERIFY") != "" {
		skipSSL = true
	}

	httpClient := &http.Client{
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
	if ca := os.Getenv("CURL_CA_BUNDLE"); ca != "" {
		TrustIcpCert(httpClient, ca)
	}

	// If we are reusing the client, store it
	if os.Getenv("EX_PERF_DONT_REUSE_HTTP_CLIENT") == "" {
		HttpClient = httpClient
	}
	return httpClient
}

//TrustIcpCert adds the icp cert file to be trusted in calls made by the given http client
func TrustIcpCert(httpClient *http.Client, certPath string) {
	icpCert, err := ioutil.ReadFile(certPath)
	if err != nil {
		Fatal(HTTP_ERROR, "Encountered error reading ICP cert file %v: %v", certPath, err)
	}
	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(icpCert)

	transport := httpClient.Transport.(*http.Transport)
	transport.TLSClientConfig.RootCAs = caCertPool
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
		TotalOps++
		if resp, err := httpClient.Do(req); err != nil {
			if IsTransportError(err) {
				if retryCount <= retryMax {
					Verbose("error calling %s %s: %v. Will retry...", req.Method, req.URL, err)
					// retry for network tranport errors
					time.Sleep(retrySleep * time.Second)
					continue
				} else {
					Fatal(HTTP_ERROR, "error calling %s %s: %v. At retry max, exiting.", req.Method, req.URL, err)
				}
			} else {
				Fatal(HTTP_ERROR, "error calling %s %s: %v", req.Method, req.URL, err)
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

// ExchangeGet runs a GET to the specified service api and fills in the specified json respStruct. If the respStruct is just a string, fill in the raw json.
// If the list of goodHttpCodes is not empty and none match the actual http code, it will exit with an error. Otherwise the actual code is returned.
func ExchangeGet(urlSuffix, credentials string, goodHttpCodes []int, respStruct interface{}) (httpCode int) {
	url := GetExchangeUrl() + "/" + urlSuffix
	apiMsg := http.MethodGet + " " + url

	Verbose(apiMsg)
	httpClient := GetHTTPClient()

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		Fatal(HTTP_ERROR, "%s new request failed: %v", apiMsg, err)
	}
	req.Header.Add("Accept", "application/json")
	if credentials != "" {
		req.Header.Add("Authorization", fmt.Sprintf("Basic %v", base64.StdEncoding.EncodeToString([]byte(credentials))))
	}

	resp := invokeRestApiWithRetry(httpClient, req)
	defer resp.Body.Close()
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		Fatal(HTTP_ERROR, "failed to read body response from %s: %v", apiMsg, err)
	}
	httpCode = resp.StatusCode
	Verbose("HTTP code: %d", httpCode)
	if !isGoodCode(httpCode, append(goodHttpCodes, 200)) {
		Error("bad HTTP code %d from %s, output: %s", httpCode, apiMsg, string(bodyBytes)) // always continue if a GET fails
	}

	if len(bodyBytes) > 0 && respStruct != nil { // some front-ends of exchange will return nothing when auth problem
		switch s := respStruct.(type) {
		case *[]byte:
			// This is the signal that they want the raw body back
			*s = bodyBytes
		case *string:
			// If the respStruct to fill in is just a string, unmarshal/remarshal it to get it in json indented form, and then return as a string
			var jsonStruct interface{}
			err = json.Unmarshal(bodyBytes, &jsonStruct)
			if err != nil {
				Fatal(HTTP_ERROR, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
			jsonBytes, err := json.MarshalIndent(jsonStruct, "", "    ")
			if err != nil {
				Fatal(HTTP_ERROR, "failed to marshal exchange output from %s: %v", apiMsg, err)
			}
			*s = string(jsonBytes)
		default:
			err = json.Unmarshal(bodyBytes, respStruct)
			if err != nil {
				Fatal(HTTP_ERROR, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
		}
	}
	return
}

// ExchangeP runs a PUT, POST, or PATCH to the exchange api to create of update a resource. If body is a string, it will be given to the exchange
// as json. Otherwise the struct will be marshaled to json.
// If the list of goodHttpCodes is not empty and none match the actual http code, it will exit with an error. Otherwise the actual code is returned.
func ExchangeP(method string, urlSuffix, credentials string, goodHttpCodes []int, body, respStruct interface{}, doContinue bool) (httpCode int) {
	url := GetExchangeUrl() + "/" + urlSuffix
	apiMsg := method + " " + url

	Verbose(apiMsg)
	httpClient := GetHTTPClient()

	// Prepare body
	var requestBody io.Reader
	if body == nil {
		requestBody = nil
	} else {
		var jsonBytes []byte
		switch b := body.(type) {
		case string:
			jsonBytes = []byte(b)
		default:
			var err error
			jsonBytes, err = json.Marshal(body)
			if err != nil {
				Fatal(JSON_PARSING_ERROR, "failed to marshal exchange body for %s: %v", apiMsg, err)
			}
		}
		requestBody = bytes.NewBuffer(jsonBytes)
	}

	// Create the request and run it
	req, err := http.NewRequest(method, url, requestBody)
	if err != nil {
		Fatal(HTTP_ERROR, "%s new request failed: %v", apiMsg, err)
	}
	req.Header.Add("Accept", "application/json")
	req.Header.Add("Content-Type", "application/json")

	if credentials != "" {
		req.Header.Add("Authorization", fmt.Sprintf("Basic %v", base64.StdEncoding.EncodeToString([]byte(credentials))))
	} // else it is an anonymous call

	resp := invokeRestApiWithRetry(httpClient, req)
	defer resp.Body.Close()
	bodyBytes, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		Fatal(HTTP_ERROR, "failed to read body response from %s: %v", apiMsg, err)
	}
	httpCode = resp.StatusCode
	Verbose("HTTP code: %d", httpCode)
	if !isGoodCode(httpCode, append(goodHttpCodes, 201)) {
		errMsg := fmt.Sprintf("bad HTTP code %d from %s, output: %s", httpCode, apiMsg, string(bodyBytes))
		if doContinue {
			Error(errMsg)
		} else {
			Fatal(HTTP_ERROR, errMsg)
		}
	}

	if len(bodyBytes) > 0 && respStruct != nil { // some front-ends of exchange will return nothing when auth problem
		switch s := respStruct.(type) {
		case *[]byte:
			// This is the signal that they want the raw body back
			*s = bodyBytes
		case *string:
			// If the respStruct to fill in is just a string, unmarshal/remarshal it to get it in json indented form, and then return as a string
			//todo: this gets it in json indented form, but also returns the fields in random order (because they were interpreted as a map)
			var jsonStruct interface{}
			err = json.Unmarshal(bodyBytes, &jsonStruct)
			if err != nil {
				Fatal(HTTP_ERROR, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
			jsonBytes, err := json.MarshalIndent(jsonStruct, "", "    ")
			if err != nil {
				Fatal(HTTP_ERROR, "failed to marshal exchange output from %s: %v", apiMsg, err)
			}
			*s = string(jsonBytes)
		default:
			err = json.Unmarshal(bodyBytes, respStruct)
			if err != nil {
				Fatal(HTTP_ERROR, "failed to unmarshal exchange body response from %s: %v", apiMsg, err)
			}
		}
	}
	return
}

// ExchangeDelete deletes a resource via the exchange api.
// If the list of goodHttpCodes is not empty and none match the actual http code, it will exit with an error. Otherwise the actual code is returned.
func ExchangeDelete(urlSuffix, credentials string, goodHttpCodes []int) (httpCode int) {
	url := GetExchangeUrl() + "/" + urlSuffix
	apiMsg := http.MethodDelete + " " + url

	Verbose(apiMsg)
	httpClient := GetHTTPClient()

	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		Fatal(HTTP_ERROR, "%s new request failed: %v", apiMsg, err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("Basic %v", base64.StdEncoding.EncodeToString([]byte(credentials))))
	resp := invokeRestApiWithRetry(httpClient, req)
	// delete never returns a body
	httpCode = resp.StatusCode
	Verbose("HTTP code: %d", httpCode)
	if !isGoodCode(httpCode, append(goodHttpCodes, 204)) {
		Error("bad HTTP code %d from %s", httpCode, apiMsg) // always continue if a DELETE fails
	}
	return
}

// Run a command with optional stdin and args, and return stdout, stderr
func RunCmd(stdinBytes []byte, commandString string, args ...string) (bool, []byte, []byte) {
	// For debug, build the full cmd string
	cmdStr := commandString
	for _, a := range args {
		cmdStr += " " + a
	}
	if stdinBytes != nil {
		cmdStr += " < stdin"
	}
	Verbose("running: %v", cmdStr)

	// Create the command object with its args
	cmd := exec.Command(commandString, args...)
	if cmd == nil {
		Fatal(EXEC_CMD_ERROR, "did not get a command object")
	}

	var stdin io.WriteCloser
	var err error
	if stdinBytes != nil {
		// Create the std in pipe
		stdin, err = cmd.StdinPipe()
		if err != nil {
			Fatal(EXEC_CMD_ERROR, "Could not get Stdin pipe, error: %v", err)
		}
		// Read the input file
		//jInbytes, err = ioutil.ReadFile(stdinFilename)
		//if err != nil { Fatal(EXEC_CMD_ERROR,"Unable to read " + stdinFilename + " file, error: %v", err) }
	}
	// Create the stdout pipe to hold the output from the command
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not retrieve output from command, error: %v", err)
	}
	// Create the stderr pipe to hold the errors from the command
	stderr, err := cmd.StderrPipe()
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not retrieve stderr from command, error: %v", err)
	}

	// Start the command, which will block for input from stdin if the cmd reads from it
	err = cmd.Start()
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "Unable to start command, error: %v", err)
	}

	if stdinBytes != nil {
		// Send in the std in bytes
		_, err = stdin.Write(stdinBytes)
		if err != nil {
			Fatal(EXEC_CMD_ERROR, "Unable to write to stdin of command, error: %v", err)
		}
		// Close std in so that the command will begin to execute
		err = stdin.Close()
		if err != nil {
			Fatal(EXEC_CMD_ERROR, "Unable to close stdin, error: %v", err)
		}
	}

	//err = error(nil)
	// Read the output from stdout and stderr into byte arrays
	// stdoutBytes, err := readPipe(stdout)
	stdoutBytes, err := ioutil.ReadAll(stdout)
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not read stdout, error: %v", err)
	}
	// stderrBytes, err := readPipe(stderr)
	stderrBytes, err := ioutil.ReadAll(stderr)
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not read stderr, error: %v", err)
	}

	// Now block waiting for the command to complete
	err = cmd.Wait()
	var exitSuccess bool
	if err == nil {
		exitSuccess = true
	} else {
		// This could be non-zero exit code or error connecting stdin, stdout, stderr. Determine which:
		exiterr, ok := err.(*exec.ExitError)
		if ok {
			// cmd had non-zero exit code
			exitSuccess = false
			// We could possibly get the exit code, but this doesn't work on all platforms, see https://stackoverflow.com/questions/10385551/get-exit-code-go
			//if status, ok := exiterr.Sys().(syscall.WaitStatus); ok {}
		} else {
			// error connecting stdin, stdout, stderr
			Fatal(EXEC_CMD_ERROR, "problem running cmd: %v, %v", exiterr, err)
		}
	}

	return exitSuccess, stdoutBytes, stderrBytes
}

func RunCmdAndCheck(commandString string, args ...string) {
	success, stdout, stderr := RunCmd(nil, commandString, args...)
	if success {
		return
	}
	errMsg := fmt.Sprintf("Command %s failed, stderr: %s\n", commandString, string(stderr))
	if len(stdout) > 0 {
		errMsg += fmt.Sprintf("stdout: %s\n", string(stdout))
	}
	Fatal(EXEC_CMD_ERROR, errMsg)
}

func ConfirmCmdsExist(cmds ...string) {
	for _, cmd := range cmds {
		success, _, _ := RunCmd(nil, "which", cmd)
		if !success {
			Fatal(CLI_INPUT_ERROR, cmd+" is not installed but required, exiting")
		}
	}
}

func MakeDir(path string) {
	err := os.MkdirAll(path, 0755)
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not create directory %s: %v", path, err)
	}
}

func RemoveFile(path string) {
	err := os.RemoveAll(path) // RemoveAll does not return an error if the file does not exist
	if err != nil {
		Fatal(EXEC_CMD_ERROR, "could not remove %s: %v", path, err)
	}
}
