#!/bin/bash

# Performance test simulating many nodes making calls to the exchange. For scale testing, run many instances of this using wrapper.sh
#todo: add optional registration exchange calls

if [[ $1 == "-h" || $1 == "--help" ]]; then
	echo "Usage: $0 [<name base>]"
	exit 0
fi

namebase="${1:-1}"

# These env vars are required
if [[ -z "$EXCHANGE_ROOTPW" || -z "$EXCHANGE_IAM_ACCOUNT_ID" || -z "$EXCHANGE_IAM_KEY" ]]; then
    echo "Error: environment variables EXCHANGE_ROOTPW, EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account), and EXCHANGE_IAM_KEY (platform API key for your cloud user) must both be set."
    exit 1
fi

scriptName="$(basename $0)"

# Test configuration. You can override these before invoking the script, if you want.
HZN_EXCHANGE_URL="${HZN_EXCHANGE_URL:-http://localhost:8080/v1}"
# Note: the namebase will be prepended to this value
EX_PERF_ORG="${EX_PERF_ORG:-performance${scriptName%%.*}}"

# default of where to write the summary or error msgs. Can be overridden
EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"
reportDir="$EX_PERF_REPORT_DIR/$scriptName"
EX_PERF_REPORT_FILE="${EX_PERF_REPORT_FILE:-$reportDir/$namebase.summary}"
# this file holds the output of the most recent curl cmd, which is useful when the script errors out
EX_PERF_DEBUG_FILE="${EX_PERF_DEBUG_FILE:-$reportDir/debug/$namebase.lastmsg}"

# The length of the performance test, measured in the number of times each node heartbeats
numHeartbeats="${EX_PERF_NUM_HEARTBEATS:-50}"
# On average each rest api takes 0.4 seconds, which means a node's 4 repeated apis would take 1.6 seconds, so this script can do that 37.5 times in the 60 s interval,
# thereby representing the load on the exchange of about 35 nodes
numNodes="${EX_PERF_NUM_NODES:-35}"
# An estimate of the average number of msgs a node will have in flight at 1 time
numMsgs="${EX_PERF_NUM_MSGS:-10}"

# These defaults are taken from /etc/horizon/anax.json
nodeHbInterval="${EX_NODE_HB_INTERVAL:-60}"
svcCheckInterval="${EX_NODE_SVC_CHECK_INTERVAL:-300}"
versionCheckInterval="${EX_NODE_VERSION_CHECK_INTERVAL:-720}"

if [[ -n "$EX_PERF_CERT_FILE" ]]; then
    certFile="--cacert $EX_PERF_CERT_FILE"
else
    certFile="-k"
fi

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EXCHANGE_ROOTPW"

# This script will create just 1 org and put everything else under that. If you use wrapper.sh to drive this, each instance of this script will use a different org.
#todo: we really should test all of the nodes being in the same org, but then we would have to coordinate objects from different script instances
orgbase="${namebase}$EX_PERF_ORG"
org="${orgbase}1"

# We test with both a local exchange user, and a cloud user.
#locuserbase="u"
#locuser="${locuserbase}1"
#pw=pw
#locuserauth="$org/$locuser:$pw"
userauth="$org/iamapikey:$EXCHANGE_IAM_KEY"

nodebase="n"
nodeid="${nodebase}1"
nodetoken=abc123
nodeauth="$org/$nodeid:$nodetoken"

agbotbase="a"
agbotid="${agbotbase}1"
agbottoken=abcdef
agbotauth="$org/$agbotid:$agbottoken"

svcurl="svcurl"
svcversion="1.2.3"
svcarchbase="arch"  # needed because we increment the arch because its the last thing in the svc id and we have to increment that
svcarch="${svcarchbase}1"
svcidbase="${svcurl}_${svcversion}_$svcarchbase"
svcid="${svcidbase}1"

patternbase="p"
patternid="${patternbase}1"

buspolbase="bp"
buspolid="${buspolbase}1"

curlBasicArgs="-sS -w %{http_code} --output $EX_PERF_DEBUG_FILE $accept $certFile"
#curlBasicArgs="-sS -w %{http_code} --output /dev/null $accept"
# curlBasicArgs="-s -w %{http_code} $accept"
# set -x

# Check the http code returned by curl. Args: returned rc, good rc, second good rc (optional)
function checkrc {
    if [[ $1 != $2 && ( -z $3 ||  $1 != $3 ) ]]; then
	    httpcode="${1:0:3}"     # when an error occurs with curl the rest method output comes in stderr with the http code
	    # write error msg to both the summary file and stderr
		errMsg="=======================================> curl $4 failed with: $1, exiting."
		echo "$errMsg" > $EX_PERF_REPORT_FILE
		echo "$errMsg" >&2
		exit $httpcode
	fi
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 != 0 ]]; then
	    # write error msg to both the summary file and stderr
		errMsg="=======================================> command $2 failed with exit code $1, exiting."
		echo "$errMsg" > $EX_PERF_REPORT_FILE
		echo "$errMsg" >&2
		exit $1
	fi
}

function divide {
	bc <<< "scale=3; $1/$2"
}

function curlget {
    #numtimes=$1
    auth=$1
    url=$2
	echo "Running GET ($auth) $url"
	#start=`date +%s`
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		rc=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$url)
		checkrc $rc 200 '' "GET $url"
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
	start=`date +%s`
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
	echo "Running $method/create ($auth) $urlbase $numtimes times:"
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
	    if [[ "$urlbase" =~ /services$ ]]; then
	        # special case for creating services, because the body needs to be incremented
            # echo curl -X $method $curlBasicArgs $content $auth -d "${body}$i\"}" $HZN_EXCHANGE_URL/$urlbase
            rc=$(curl -X $method $curlBasicArgs $content $auth -d "${body}$i\"}" $HZN_EXCHANGE_URL/$urlbase)
        else
            # echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i
            rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$urlbase$i)
        fi
		checkrc $rc 201 '' "$method $urlbase$i"
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST/PATCH, auth, url, body
function curlputpost {
    method=$1
    #numtimes=$2
    auth=$2
    url=$3
    body="$4"
	echo "Running $method ($auth) $url"
	#start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	#local i
	#for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			rc=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkrc $rc 201 '' "$method $url"
		#echo -n .
		bignum=$(($bignum+1))
	#done
	#total=$(($(date +%s)-start))
	#echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST/PATCH, numtimes, auth, url, body
function curlmultiputpost {
    method=$1
    numtimes=$2
    auth=$3
    url=$4
    body="$5"
	echo "Running $method ($auth) $url $numtimes times:"
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local i
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			rc=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
		else
			# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url
			rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $HZN_EXCHANGE_URL/$url)
		fi
		checkrc $rc 201 '' "$method $url"
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

function curldelete {
    numtimes="$1"
    auth=$2
    urlbase=$3
    if [[ $4 == "NOT_FOUND_OK" ]]; then
        secondcode=404
    else
        secondcode=''
    fi
	echo "Running DELETE ($auth) $urlbase $numtimes times:"
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	local i
    for (( i=1 ; i<=$numtimes ; i++ )) ; do
        echo curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$urlbase$i
        rc=$(curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$urlbase$i)
        checkrc $rc 204 $secondcode "DELETE $urlbase$i"
        echo -n .
        #echo $rc
        bignum=$(($bignum+1))
    done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Get all the msgs for this node and delete them 1 by 1
function deletemsgs {
    auth=$1
    urlbase=$2
	echo "Deleting ($auth) $urlbase..."
	start=`date +%s`
	#msgNums='1 2'
	msgNums=$(curl -X GET -sS $accept -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$urlbase | jq '.messages[].msgId')
	# echo "msgNums: $msgNums"
    checkexitcode $? "GET msg numbers"
    if [[ msgNums == "" ]]; then echo "=======================================> GET msgs returned no output."; fi
    bignum=$(($bignum+1))

	local i
	for i in $msgNums; do
		# echo curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$urlbase/$i
		rc=$(curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $HZN_EXCHANGE_URL/$urlbase/$i)
		checkrc $rc 204 '' "DELETE $urlbase/$i"
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: POST/GET, auth, url
function curladmin {
    method=$1
    auth=$2
    url=$3
	echo "Running $method ($auth) $url:"
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    rc=$(curl -X $method $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)
    checkrc $rc 201 '' "$method $url"
    bignum=$(($bignum+1))
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=1, each=${total}s"
}

bignum=0
bigstart=`date +%s`


#=========== Initialization =================================================

echo "Initializing test:"

echo mkdir -p "$reportDir" "$(dirname $EX_PERF_DEBUG_FILE)"
mkdir -p "$reportDir" "$(dirname $EX_PERF_DEBUG_FILE)"

# Get rid of anything left over from a previous run and then create the org
curldelete 1 "$rootauth" "orgs/$orgbase" "NOT_FOUND_OK"
curladmin "POST" "$rootauth" "admin/clearauthcaches"  # needed to avoid an obscure bug: in the prev run the ibm auth cache was populated and user created, but this run when the org and user are deleted, if the cache entry has not expired the user will not get recreated
curlcreate "POST" 1 "$rootauth" "orgs/$orgbase" '{ "label": "perf test org", "description": "blah blah", "orgType":"IBM", "tags": { "ibmcloud_id": "'$EXCHANGE_IAM_ACCOUNT_ID'" } }'

# Get cloud user to verify it is valid
curlget $userauth "orgs/$org/users/iamapikey"

# Create services, 1 for each node
curlcreate "POST" $numNodes $userauth "orgs/$org/services" '{"label": "svc", "public": true, "url": "'$svcurl'", "version": "'$svcversion'", "sharable": "singleton",
  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "'$svcarchbase    # the ending '" }' will be added by curlcreate

# Post (create) patterns p*, 1 for each node, that all use the same service
curlcreate "POST" $numNodes $userauth "orgs/$org/patterns/$patternbase" '{"label": "pat", "public": true, "services": [{ "serviceUrl": "'$svcurl'", "serviceOrgid": "'$org'", "serviceArch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] }],
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

# Put (create) agbot a1 to be able to create node msgs
curlcreate "PUT" 1 $userauth "orgs/$org/agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'

# Post (create) node n* msgs
for (( i=1 ; i<=$numNodes ; i++ )) ; do
    curlmultiputpost "POST" $numMsgs $agbotauth "orgs/$org/nodes/$nodebase$i/msgs" '{"message": "hey there", "ttl": 8640000}'   # ttl is 2400 hours
done

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

for (( h=1 ; h<=$numHeartbeats ; h++ )) ; do
    echo "Node heartbeat $h"
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
            # Post admin/version
            curlget $mynodeauth admin/version
        fi

        # Put (update) node/n1/status
        #curlputpost "PUT" $nodeauth "orgs/$org/nodes/$nodeid/status" '{ "connectivity": {"firmware.bluehorizon.network": true}, "services": [] }'

    done

    if [[ $svcCheckCount -ge $svcCheckInterval ]]; then
        svcCheckCount=0
    fi
    if [[ $versionCheckCount -ge $versionCheckInterval ]]; then
        versionCheckCount=0
    fi

done

#=========== Clean up ===========================================

printf "\nCleaning up from test:\n"

# Delete org to make sure everything is completely gone
curldelete 1 $rootauth "orgs/$orgbase"

bigtotal=$(($(date +%s)-bigstart))
sumMsg="Overall: total=${bigtotal}s, num=$bignum, each=$(divide $bigtotal $bignum)s"
echo "$sumMsg" > $EX_PERF_REPORT_FILE
printf "\n$sumMsg\n"
