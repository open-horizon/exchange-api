#!/bin/bash

# Performance test simulating many nodes making calls to the exchange. For scale testing, run many instances of this using wrapper.sh
#todo: add optional registration exchange calls

if [[ $1 == "-h" || $1 == "--help" ]]; then
	echo "Usage: $0 [<name base>]"
	exit 0
fi

namebase="${1:-1}-node"

# These env vars are required
if [[ -z "$EXCHANGE_ROOTPW" || -z "$EXCHANGE_IAM_KEY" || -z "$EXCHANGE_IAM_EMAIL" ]]; then
    echo "Error: environment variables EXCHANGE_ROOTPW, EXCHANGE_IAM_KEY (platform API key for your cloud user), EXCHANGE_IAM_EMAIL must all be set."
    exit 1
fi
# Setting EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account) distinguishes this as an ibm public cloud environment, instead of ICP

scriptName="$(basename $0)"

# Test configuration. You can override these before invoking the script, if you want.
HZN_EXCHANGE_URL="${HZN_EXCHANGE_URL:-http://localhost:8080/v1}"
#EX_PERF_ORG="${EX_PERF_ORG:-performance${scriptName%%.*}}"
EX_PERF_ORG="${EX_PERF_ORG:-performancenodeagbot}"

# default of where to write the summary or error msgs. Can be overridden
EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"
reportDir="$EX_PERF_REPORT_DIR/$scriptName"
EX_PERF_REPORT_FILE="${EX_PERF_REPORT_FILE:-$reportDir/$namebase.summary}"
# this file holds the output of the most recent curl cmd, which is useful when the script errors out
EX_PERF_DEBUG_FILE="${EX_PERF_DEBUG_FILE:-$reportDir/debug/$namebase.lastmsg}"

# The length of the performance test, measured in the number of times each node heartbeats (by default 60 sec each)
numHeartbeats="${EX_PERF_NUM_HEARTBEATS:-15}"
# On average each rest api takes 0.4 seconds, which means a node's 4 repeated apis would take 1.6 seconds, so this script can do that 37.5 times in the 60 s interval,
# thereby representing the load on the exchange of about 35 nodes
numNodes="${EX_PERF_NUM_NODES:-50}"
# How many nodes should be given an agreement each hb interval
numNodeAgreements="${EX_PERF_NUM_NODE_AGREEMENTS:-7}"
# An estimate of the average number of msgs a node will have in flight at 1 time
numMsgs="${EX_PERF_NUM_MSGS:-5}"
# create this many extra svcs so the nodes and patterns have to search thru them, but we will just use a primary/common svc for the pattern this group of nodes will use
numSvcs="${EX_PERF_NUM_SVCS:-4}"
# create multiple patterns so the agbot has to serve them all, but we will just use the 1st one for this group of nodes
numPatterns="${EX_PERF_NUM_PATTERNS:-2}"

# These defaults are taken from /etc/horizon/anax.json
nodeHbInterval="${EX_NODE_HB_INTERVAL:-60}"
svcCheckInterval="${EX_NODE_SVC_CHECK_INTERVAL:-300}"
versionCheckInterval="${EX_NODE_VERSION_CHECK_INTERVAL:-720}"

# CURL_CA_BUNDLE can be exported in our parent if a self-signed cert is needed.

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EXCHANGE_ROOTPW"

# This script will create just 1 org and put everything else under that. If you use wrapper.sh, all instances of this script and agbot.sh should use the same org.
#orgbase="${namebase}$EX_PERF_ORG"
orgbase="$EX_PERF_ORG"
org="${orgbase}"

# Test with a cloud user.
if [[ -n "$EXCHANGE_IAM_ACCOUNT_ID" ]]; then
    userauth="$org/iamapikey:$EXCHANGE_IAM_KEY"
else
    # for ICP we can't play the game of having our own org, but associating it with another account, so we have to use a local exchange user
    userauth="$org/$EXCHANGE_IAM_EMAIL:$EXCHANGE_IAM_KEY"
fi

# since the objects from all instances of this script are in the same org, their names need to be unique by starting with namebase
nodebase="${namebase}-n"
nodeid="${nodebase}1"
nodetoken=abc123
nodeauth="$org/$nodeid:$nodetoken"

nodeagrbase="${namebase}-node-agr"   # agreement ids must be unique

# this agbot id can not conflict with the agbots that agbot.sh creates
agbotbase="${namebase}-a"
agbotid="${agbotbase}1"
agbottoken=abcdef
agbotauth="$org/$agbotid:$agbottoken"

# svcurlbase is for creating the extra svcs. svcurl is the primary/common svc that all of the patterns will use
svcurlbase="${namebase}-svcurl"
svcurl="nodeagbotsvc"
svcversion="1.2.3"
svcarch="amd64"
svcid="${svcurl}_${svcversion}_$svcarch"

patternbase="${namebase}-p"
patternid="${patternbase}1"

buspolbase="${namebase}-bp"
buspolid="${buspolbase}1"

curlBasicArgs="-sS -w %{http_code} --output $EX_PERF_DEBUG_FILE $accept"
#curlBasicArgs="-sS -w %{http_code} --output /dev/null $accept"
# curlBasicArgs="-s -w %{http_code} $accept"
# set -x

# Check the http code returned by curl.
# Most of the curl invocations use --output, so the only thing that comes to stdout is the http code.
function checkhttpcode {
    httpcode=$1
    okcodes=$2
    msg="$3"
    cont=$4
    if [[ $okcodes =~ $httpcode ]]; then return; fi
    if [[ "$cont" == 'continue' ]]; then
        nextAction='continuing'
    else
        nextAction='exiting'
    fi
    # write error msg to both the summary file and stderr
    errMsg="===============> curl $msg failed with: $httpcode, $nextAction"
    echo "$errMsg" >> $EX_PERF_REPORT_FILE
    echo "$errMsg" >&2
    if [[ "$cont" != 'continue' ]]; then exit $httpcode; fi
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 != 0 ]]; then
	    # write error msg to both the summary file and stderr
		errMsg="===============> command $2 failed with exit code $1, exiting."
		echo "$errMsg" >> $EX_PERF_REPORT_FILE
		echo "$errMsg" >&2
		if [[ "$3" != 'continue' ]]; then exit $1; fi
	fi
}

function divide {
	bc <<< "scale=3; $1/$2"
}

function curlget {
    #numtimes=$1
    auth=$1
    url=$2
    otherRcs=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running GET ($auth) $url"; fi
	#start=`date +%s`
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		httpcode=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$url)
		checkhttpcode $httpcode "200 $otherRcs" "GET $url"
		#echo -n .
		bignum=$(($bignum+1))
	#done
	#total=$(($(date +%s)-start))
	#echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

function curlcreate {
    method=$1
    numtimes=$2
    auth="$3"
    urlbase=$4
    body=$5
    otherRcs=$6
	start=`date +%s`
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
	if [[ -n "$VERBOSE" ]]; then echo "Running $method/create ($auth) $urlbase $numtimes times:"; fi
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
        # echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i
        httpcode=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i)
		checkhttpcode $httpcode "201 $otherRcs" "$method $urlbase$i"
		if [[ -n "$VERBOSE" ]]; then echo -n .; fi
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

# Create just 1 object in the case in which the call needs to increment something in the body (e.g. w/services)
function curlcreateone {
    method=$1
    auth="$2"
    url=$3
    body=$4
    otherRcs=$5
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
	if [[ -n "$VERBOSE" ]]; then echo "Running $method/create ($auth) $url"; fi
    # echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
    httpcode=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode "201 $otherRcs" "$method $url"
    bignum=$(($bignum+1))
}

# Args: PUT/POST/PATCH, auth, url, body
function curlputpost {
    method=$1
    #numtimes=$2
    auth=$2
    url=$3
    body="$4"
    otherRcs=$5
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url"; fi
	#start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			httpcode=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			httpcode=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkhttpcode $httpcode "201 $otherRcs" "$method $url"
		#echo -n .
		bignum=$(($bignum+1))
	#done
	#total=$(($(date +%s)-start))
	#echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST/PATCH, numtimes, auth, url, body
function curlputpostmulti {
    method=$1
    numtimes=$2
    auth=$3
    url=$4
    body="$5"
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url $numtimes times:"; fi
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			httpcode=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			httpcode=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkhttpcode $httpcode 201 "$method $url"
		if [[ -n "$VERBOSE" ]]; then echo -n .; fi
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

function curldelete {
    numtimes="$1"
    auth=$2
    urlbase=$3
    otherRcs=$4
	if [[ -n "$VERBOSE" ]]; then echo "Running DELETE ($auth) $urlbase $numtimes times:"; fi
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local i
    for (( i=1 ; i<=$numtimes ; i++ )) ; do
        #echo curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$urlbase$i
        httpcode=$(curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$urlbase$i)
        checkhttpcode $httpcode "204 $otherRcs" "DELETE $urlbase$i"
        if [[ -n "$VERBOSE" ]]; then echo -n .; fi
        #echo $rc
        bignum=$(($bignum+1))
    done
	total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"; fi
}

function curldeleteone {
    auth=$1
    url=$2
    otherRcs=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running DELETE ($auth) $url"; fi
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    #echo curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url
    httpcode=$(curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode "204 $otherRcs" "DELETE $url"
    bignum=$(($bignum+1))
}

# Get all the msgs for this node and delete them 1 by 1
#function deletemsgs {
#    auth=$1
#    urlbase=$2
#	echo "Deleting ($auth) $urlbase..."
#	start=`date +%s`
#	msgNums=$(curl -X GET -sS $accept -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$urlbase | jq '.messages[].msgId')
#    checkexitcode $? "GET msg numbers"
#    if [[ msgNums == "" ]]; then echo "=======================================> GET msgs returned no output."; fi
#    bignum=$(($bignum+1))
#	local i
#	for i in $msgNums; do
#		httpcode=$(curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$urlbase/$i)
#		checkhttpcode $httpcode 204 "DELETE $urlbase/$i"
#		echo -n .
#		bignum=$(($bignum+1))
#	done
#	total=$(($(date +%s)-start))
#	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
#}

# Args: POST/GET, auth, url
function curladmin {
    method=$1
    auth=$2
    url=$3
	if [[ -n "$VERBOSE" ]]; then echo "Running $method ($auth) $url:"; fi
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    httpcode=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
    checkhttpcode $httpcode 201 "$method $url"
    bignum=$(($bignum+1))
	total=$(($(date +%s)-start))
	if [[ -n "$VERBOSE" ]]; then echo " total=${total}s, num=1, each=${total}s"; fi
}

bignum=0
bigstart=`date +%s`


#=========== Initialization =================================================

echo "Initializing test:"

echo "Using exchange $HZN_EXCHANGE_URL"

#echo mkdir -p "$reportDir" "$(dirname $EX_PERF_DEBUG_FILE)"
mkdir -p "$reportDir" "$(dirname $EX_PERF_DEBUG_FILE)"
rm -f $EX_PERF_REPORT_FILE    # do not need to delete the debug file, because every cmd overwrites it

# Can not delete the org in case other instances of this script are using it. Whoever calls this script must delete it afterward.
#curldelete 1 "$rootauth" "orgs/$orgbase" 404
# /admin/clearauthcaches is causing an unusual problem and it is not worth debug, because the cache implementation will be changing, and issue 176 will address the problem clearauthcaches is trying to solve in this case.
#curladmin "POST" "$rootauth" "admin/clearauthcaches"  # to avoid an obscure bug: in the prev run the ibm auth cache was populated and user created, but then the org (and user) is deleted and then recreated, the user will not get recreated until the cache entry expires
# this is tolerant of the org already existing
if [[ -n "$EXCHANGE_IAM_ACCOUNT_ID" ]]; then
    # this is an IBM public cloud instance
    curlcreateone "POST" "$rootauth" "orgs/$org" '{ "label": "perf test org", "description": "blah blah", "tags": { "ibmcloud_id": "'$EXCHANGE_IAM_ACCOUNT_ID'" } }' 403
    curlcreateone "PUT" "$rootauth" "orgs/$org/users/$EXCHANGE_IAM_EMAIL" '{"password": "foobar", "admin": false, "email": "'$EXCHANGE_IAM_EMAIL'"}'  # needed until issue 176 is fixed
    curlget $userauth "orgs/$org/users/iamapikey" 504   #todo: remove 504 once exchange 1.78.0 is deployed everywhere
else
    # ICP
    curlcreateone "POST" "$rootauth" "orgs/$org" '{ "label": "perf test org", "description": "blah blah" }' 403
    curlcreateone "PUT" "$rootauth" "orgs/$org/users/$EXCHANGE_IAM_EMAIL" '{"password": "'$EXCHANGE_IAM_KEY'", "admin": false, "email": "'$EXCHANGE_IAM_EMAIL'"}'  # needed until issue 176 is fixed
    curlget $userauth "orgs/$org/users/$EXCHANGE_IAM_EMAIL"
fi


# Create the primary/common svc that all the patterns use
curlcreateone "POST" $userauth "orgs/$org/services" '{"label": "svc", "public": true, "url": "'$svcurl'", "version": "'$svcversion'", "sharable": "singleton",
  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "'$svcarch'" }' 403

# Create extra services
for (( i=1 ; i<=$numSvcs ; i++ )) ; do
    curlcreateone "POST" $userauth "orgs/$org/services" '{"label": "svc", "public": true, "url": "'$svcurlbase$i'", "version": "'$svcversion'", "sharable": "singleton",
      "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "'$svcarch'" }'
done

# Post (create) patterns p*, that all use the 1st service
curlcreate "POST" $numPatterns $userauth "orgs/$org/patterns/$patternbase" '{"label": "pat", "public": false, "services": [{ "serviceUrl": "'$svcurl'", "serviceOrgid": "'$org'", "serviceArch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] }],
  "userInput": [{
      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [{ "name": "VERBOSE", "value": true }]
  }]
}'

# Put (create) nodes n*
curlcreate "PUT" $numNodes $userauth "orgs/$org/nodes/$nodebase" '{"token": "'$nodetoken'", "name": "pi", "pattern": "'$org'/'$patternid'", "arch": "'$svcarch'", "registeredServices": [{"url": "'$org'/'$svcurl'", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "'$svcarch'", "propType": "string", "op": "in"},{"name": "version", "value": "1.0.0", "propType": "version", "op": "in"}]}], "publicKey": "ABC"}'

# Put (create) node/n*/policy
for (( i=1 ; i<=$numNodes ; i++ )) ; do
    curlputpost "PUT" $userauth "orgs/$org/nodes/$nodebase$i/policy" '{ "properties": [{"name":"purpose", "value":"testing", "type":"string"}], "constraints":["a == b"] }'
done

# Put (create) 1 agbot to be able to create node msgs
curlcreateone "PUT" $userauth "orgs/$org/agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'

# Post (create) node n* msgs
for (( i=1 ; i<=$numNodes ; i++ )) ; do
    curlputpostmulti "POST" $numMsgs $agbotauth "orgs/$org/nodes/$nodebase$i/msgs" '{"message": "hey there", "ttl": 8640000}'   # ttl is 2400 hours
done

#todo: add policy calls
# Put (update) services/${svdid}1/policy
#curlputpost "PUT" $userauth "orgs/$org/services/$svcid/policy" '{ "properties": [{"name":"purpose", "value":"location", "type":"string"}], "constraints":["a == b"] }'

# Post (create) business policies bp*
#curlcreate "POST" $EX_NUM_BUSINESSPOLICIES $userauth "orgs/$org/business/policies/$buspolbase" '{"label": "buspol", "service": { "name": "'$svcurl'", "org": "'$org'", "arch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] },
#  "userInput": [{
#      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
#      "inputs": [{ "name": "VERBOSE", "value": true }]
#  }],
#  "properties": [{"name":"purpose", "value":"location", "type":"string"}],
#  "constraints":["a == b"]
#}'


#=========== Registration =================================================

# Put (create) node n*
#curlcreate "PUT" $numNodes $userauth "orgs/$org/nodes/$nodebase" '{"token": "'$nodetoken'", "name": "pi", "pattern": "'$org'/'$patternid'", "arch": "'$svcarch'", "registeredServices": [{"url": "'$org'/'$svcurl'", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "'$svcarch'", "propType": "string", "op": "in"},{"name": "version", "value": "1.0.0", "propType": "version", "op": "in"}]}], "publicKey": "ABC"}'

# Put (update) node/n1/policy
#curlputpost "PUT" $nodeauth "orgs/$org/nodes/$nodeid/policy" '{ "properties": [{"name":"purpose", "value":"testing", "type":"string"}], "constraints":["a == b"] }'


#=========== Loop thru repeated exchange calls =================================================

# The repeated rest apis a node runs are:
#   GET /orgs/<org>/nodes/<node> (these 4 every 60 seconds)
#   GET /orgs/<org>/nodes/<node>/msgs
#   POST /orgs/<org>/nodes/<node>/heartbeat
#   GET /orgs/<org>/nodes/<node>/policy

#   GET /orgs/<org>/services (every 300 seconds)

#   GET /admin/version (every 720 seconds)

printf "\nRunning $numHeartbeats heartbeats for $numNodes nodes:\n"
svcCheckCount=0
versionCheckCount=0
nextNodeAgreement=1
iterDeltaTotal=0

for (( h=1 ; h<=$numHeartbeats ; h++ )) ; do
    echo "Node heartbeat $h"
	startIteration=`date +%s`
    # We assume 1 hb of all the nodes takes nodeHbInterval seconds, so increment our other counts by that much
    svcCheckCount=$(( $svcCheckCount + $nodeHbInterval ))
    versionCheckCount=$(( $versionCheckCount + $nodeHbInterval ))

    for (( n=1 ; n<=$numNodes ; n++ )) ; do
        echo "Node $n"
        mynodeauth="$org/$nodebase$n:$nodetoken"

        # These api methods are run every hb:
        # Get my node
        curlget $mynodeauth "orgs/$org/nodes/$nodebase$n"

        # Get my node msgs
        curlget $mynodeauth "orgs/$org/nodes/$nodebase$n/msgs"

        # Post node/n1/heartbeat
        curlputpost "POST" $mynodeauth "orgs/$org/nodes/$nodebase$n/heartbeat"

        # Get my node policy
        curlget $mynodeauth "orgs/$org/nodes/$nodebase$n/policy"

        # If it is time to do a service check, do that
        if [[ $svcCheckCount -ge $svcCheckInterval ]]; then
            # Get all services
            curlget $mynodeauth "orgs/$org/services"
        fi

        # If it is time to do a version check, do that
        if [[ $versionCheckCount -ge $versionCheckInterval ]]; then
            # Get admin/version
            curlget $mynodeauth admin/version
        fi

        # Put (update) node/n1/status
        #curlputpost "PUT" $nodeauth "orgs/$org/nodes/$nodeid/status" '{ "connectivity": {"firmware.bluehorizon.network": true}, "services": [] }'

    done

    # Give numNodeAgreements nodes an agreement
    for (( n=$nextNodeAgreement ; n<$(($nextNodeAgreement+$numNodeAgreements)) ; n++ )) ; do
        if [[ $n -gt $numNodes ]]; then break; fi
        mynodeauth="$org/$nodebase$n:$nodetoken"
        curlcreateone "PUT" $mynodeauth "orgs/$org/nodes/$nodebase$n/agreements/$nodeagrbase$n" '{"services": [], "agreementService": {"orgid": "'$org'", "pattern": "'$org'/'$patternid'", "url": "'$org'/'$svcurl'"}, "state": "negotiating"}'
    done
    nextNodeAgreement=$(( $nextNodeAgreement + $numNodeAgreements ))

    # Reset our counters if appropriate
    if [[ $svcCheckCount -ge $svcCheckInterval ]]; then
        svcCheckCount=0
    fi
    if [[ $versionCheckCount -ge $versionCheckInterval ]]; then
        versionCheckCount=0
    fi

    # If we completed this iteration in less than nodeHbInterval, sleep the rest of the time
	iterTime=$(($(date +%s)-startIteration))
	iterDelta=$(( $nodeHbInterval - $iterTime ))
	iterDeltaTotal=$(( $iterDeltaTotal + $iterDelta ))
	if [[ $iterDelta -gt 0 ]]; then
	    echo "Sleeping for $iterDelta seconds at the end of node heartbeat $h of $numHeartbeats because loop iteration finished early"
	    sleep $iterDelta
	fi

done

#=========== Clean up ===========================================

printf "\nCleaning up from test:\n"

# Not necessary, will get deleted when the node is deleted: Delete node n* msgs
#for (( i=1 ; i<=$numNodes ; i++ )) ; do
#    deletemsgs $userauth "orgs/$org/nodes/$nodebase$i/msgs"
#done

# Delete patterns p*
curldelete $numPatterns $userauth "orgs/$org/patterns/$patternbase"

# Delete primary/commpon service and extra services
curldeleteone $userauth "orgs/$org/services/$svcid" 404
for (( i=1 ; i<=$numSvcs ; i++ )) ; do
    mysvcid="${svcurlbase}${i}_${svcversion}_$svcarch"
    curldeleteone $userauth "orgs/$org/services/$mysvcid"
done

# Delete node n*
curldelete $numNodes $userauth "orgs/$org/nodes/$nodebase"

# Delete agbot
curldeleteone $userauth "orgs/$org/agbots/$agbotid"

# Can not delete the org in case other instances of this script are still using it. Whoever calls this script must delete it.
#curldelete 1 $rootauth "orgs/$orgbase"

iterDeltaAvg=$(divide $iterDeltaTotal $numHeartbeats)
bigtotal=$(($(date +%s)-bigstart))
bigAvg=$(divide $bigtotal $bignum)

sumMsg="Simulated $numNodes nodes for $numHeartbeats heartbeats
Overall stats: total time=${bigtotal} s, num ops=$bignum, avg=$bigAvg s/op, avg iteration delta=$iterDeltaAvg s"

printf "$sumMsg\n" >> $EX_PERF_REPORT_FILE
printf "\n$sumMsg\n"
