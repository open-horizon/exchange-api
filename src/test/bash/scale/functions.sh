#!/bin/bash

# Common bash functions used by both node.sh and agbot.sh

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

# Check the http code returned by curl.
# Most of the curl invocations use --output, so the only thing that comes to stdout is the http code.
function checkhttpcode {
    local httpcode=$1
    local okcodes=$2
    local msg="$3"
    local cont=$4
    if [[ $okcodes =~ $httpcode ]]; then return; fi

    # An error occurred
    local nextAction
    if [[ "$cont" == 'continue' ]]; then
        nextAction='continuing'
    else
        nextAction='exiting'
    fi
    # write error msg to both the summary file and stderr
    local errMsg="Error:=====> $(date) curl $msg failed with: $httpcode, $nextAction"
    echo "$errMsg" >> $EX_PERF_REPORT_FILE
    # grab the error msg from the debug file
    cat $EX_PERF_DEBUG_FILE >> $EX_PERF_REPORT_FILE
    printf "\n" >> $EX_PERF_REPORT_FILE
    # also send the error summary to stderr
    echo "$errMsg" >&2

    # save off the error msg and output from the curl cmd, in case we are continuing
    #echo "$errMsg" >> $EX_PERF_ERROR_FILE
    #cat $EX_PERF_DEBUG_FILE >> $EX_PERF_ERROR_FILE
    #printf "\n" >> $EX_PERF_ERROR_FILE

    if [[ "$cont" != 'continue' ]]; then exit $httpcode; fi
}

# Check the exit code of the cmd that was run
#function checkexitcode {
#	if [[ $1 == 0 ]]; then return; fi
#    # write error msg to both the summary file and stderr
#    local nextAction
#    if [[ "$5" == 'continue' ]]; then
#        nextAction='continuing'
#    else
#        nextAction='exiting'
#    fi
#    local errMsg="===============> command $2 failed with exit code $1, $nextAction."
#    echo "$errMsg" >> $EX_PERF_REPORT_FILE
#    echo "$errMsg" >&2
#    if [[ "$3" != 'continue' ]]; then exit $1; fi
#}

function confirmcmds {
    for c in $*; do
        #echo "checking $c..."
        if ! which $c >/dev/null; then
            echo "Error: $c is not installed but required, exiting"
            exit 2
        fi
    done
}

function divide {
	bc <<< "scale=3; $1/$2"
}

function max {
    if [[ $1 -ge $2 ]]; then
        echo $1
    else
        echo $2
    fi
}

function min {
    if [[ $1 -le $2 ]]; then
        echo $1
    else
        echo $2
    fi
}

function wordCount {
    # Convert to array and return length
    local words=( $1 )
    echo ${#words[@]}
    #wc -w <<< "$1"
}

function curlget {
    #numtimes=$1
    local auth=$1
    local url=$2
    local otherRcs=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running GET ($auth) $url"; fi
	#start=`date +%s`
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		local httpcode=$(curl -X GET $CURL_BASIC_ARGS -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$url)
		checkhttpcode $httpcode "200 $otherRcs" "GET $url" 'continue'
		#echo -n .
		bignum=$(($bignum+1))
	#done
	#total=$(($(date +%s)-start))
	#echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

function curlcreate {
    local method=$1
    local numtimes=$2
    local auth="$3"
    local urlbase=$4
    local body=$5
    local otherRcs=$6
    local cont=$7
	local start=`date +%s`
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
	if [[ -n "$VERBOSE" ]]; then echo "Running $method/create ($auth) $urlbase $numtimes times:"; fi
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
        # echo curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i
        local httpcode=$(curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i)
		checkhttpcode $httpcode "201 $otherRcs" "$method $urlbase$i" $cont
		if [[ -n "$VERBOSE" ]]; then echo -n .; fi
		bignum=$(($bignum+1))
	done
	local total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

# Create just 1 object in the case in which the call needs to increment something in the body (e.g. w/services)
function curlcreateone {
    local method=$1
    local auth="$2"
    local url=$3
    local body=$4
    local otherRcs=$5
    local cont=$6
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
	if [[ -n "$VERBOSE" ]]; then echo "Running $method/create ($auth) $url"; fi
    # echo curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
    local httpcode=$(curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode "201 $otherRcs" "$method $url" $cont
    bignum=$(($bignum+1))
}

# Args: PUT/POST/PATCH, auth, url, body
function curlputpost {
    local method=$1
    #numtimes=$2
    local auth=$2
    local url=$3
    local body="$4"
    local otherRcs=$5
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url"; fi
	#start=`date +%s`
	local auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local httpcode
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			httpcode=$(curl -X $method $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			httpcode=$(curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkhttpcode $httpcode "201 $otherRcs" "$method $url" 'continue'
		#echo -n .
		bignum=$(($bignum+1))
	#done
	#total=$(($(date +%s)-start))
	#echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST/PATCH, numtimes, auth, url, body
function curlputpostmulti {
    local method=$1
    local numtimes=$2
    local auth=$3
    local url=$4
    local body="$5"
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url $numtimes times:"; fi
	local start=`date +%s`
	local auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local httpcode
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			httpcode=$(curl -X $method $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			httpcode=$(curl -X $method $CURL_BASIC_ARGS $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkhttpcode $httpcode 201 "$method $url" 'continue'
		if [[ -n "$VERBOSE" ]]; then echo -n .; fi
		bignum=$(($bignum+1))
	done
	local total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

function curldelete {
    local numtimes="$1"
    local auth=$2
    local urlbase=$3
    local otherRcs=$4
	if [[ -n "$VERBOSE" ]]; then echo "Running DELETE ($auth) $urlbase $numtimes times:"; fi
	local start=`date +%s`
	local auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local httpcode
	local i
    for (( i=1 ; i<=$numtimes ; i++ )) ; do
        #echo curl -X DELETE $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$urlbase$i
        httpcode=$(curl -X DELETE $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$urlbase$i)
        checkhttpcode $httpcode "204 $otherRcs" "DELETE $urlbase$i" 'continue'
        if [[ -n "$VERBOSE" ]]; then echo -n .; fi
        #echo $rc
        bignum=$(($bignum+1))
    done
	local total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

function curldeleteone {
    local auth=$1
    local url=$2
    local otherRcs=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running DELETE ($auth) $url"; fi
	local auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    #echo curl -X DELETE $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$url
    local httpcode=$(curl -X DELETE $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode "204 $otherRcs" "DELETE $url" 'continue'
    bignum=$(($bignum+1))
}

# Args: POST/GET, auth, url
function curladmin {
    local method=$1
    local auth=$2
    local url=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url:"; fi
	local start=`date +%s`
	local auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    local httpcode=$(curl -X $method $CURL_BASIC_ARGS $auth $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode 201 "$method $url"
    bignum=$(($bignum+1))
	local total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=1, each=${total}s"; fi
}
