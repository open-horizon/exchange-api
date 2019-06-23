#!/bin/bash

# Performance test of exchange. For scale testing, run many instances of this using wrapper.sh

if [[ $1 == "-h" || $1 == "--help" ]]; then
	echo "Usage: $0 [<name base>]"
	exit 0
fi

if [[ -n $1 ]]; then
	namebase=$1
else
	namebase="1"
fi

# These env vars are required
if [[ -z "$EXCHANGE_IAM_ACCOUNT_ID" || -z "$EXCHANGE_IAM_KEY" ]]; then
    echo "Error: environment variables EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account) and EXCHANGE_IAM_KEY (platform API key for your cloud user) must both be set."
    exit 1
fi

# Test configuration. You can override these before invoking the script, if you want.
EX_PERF_REPORT_FILE="${EX_PERF_REPORT_FILE:-/tmp/exchangePerf/$namebase.summary}"
EX_PERF_DEBUG_FILE="${EX_PERF_DEBUG_FILE:-/tmp/exchangePerf/debug/$namebase.lastmsg}"
EX_PERF_SCALE="${EX_PERF_SCALE:-10}"
EX_NUM_USERS="${EX_NUM_USERS:-$((2 * $EX_PERF_SCALE))}"
EX_NUM_NODES="${EX_NUM_NODES:-$((4 * $EX_PERF_SCALE))}"
EX_NUM_AGBOTS="${EX_NUM_AGBOTS:-$((2 * $EX_PERF_SCALE))}"
EX_NUM_AGREEMENTS="${EX_NUM_AGREEMENTS:-$((4 * $EX_PERF_SCALE))}"
EX_NUM_MSGS="${EX_NUM_MSGS:-$((4 * $EX_PERF_SCALE))}"
EX_NUM_SVCS="${EX_NUM_SVCS:-$((2 * $EX_PERF_SCALE))}"
EX_NUM_PATTERNS="${EX_NUM_PATTERNS:-$((2 * $EX_PERF_SCALE))}"
EX_NUM_BUSINESSPOLICIES="${EX_NUM_BUSINESSPOLICIES:-$((2 * $EX_PERF_SCALE))}"
EX_PERF_REPEAT="${EX_PERF_REPEAT:-$((3 * $EX_PERF_SCALE))}"
EX_PERF_ORG="${EX_PERF_ORG:-PerformanceTest}"
EX_URL_ROOT="${EXCHANGE_URL_ROOT:-http://localhost:8080}"
EX_ROOT_PW="${EXCHANGE_ROOTPW:-rootpw}"	# this has to match what is in the exchange config.json

echo mkdir -p $(dirname $EX_PERF_REPORT_FILE) $(dirname $EX_PERF_DEBUG_FILE)
mkdir -p $(dirname $EX_PERF_REPORT_FILE) $(dirname $EX_PERF_DEBUG_FILE)

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EX_ROOT_PW"

# This script will create just 1 org and put everything else under that. If you use wrapper.sh to drive this,
# each instance of this script will use a different org.
orgbase="${namebase}$EX_PERF_ORG"
org="${orgbase}1"

# We test with both a local exchange user, and a cloud user.
locuserbase="u"
locuser="${locuserbase}1"
pw=pw
locuserauth="$org/$locuser:$pw"
userauth="$org/iamapikey:$EXCHANGE_IAM_KEY"

nodebase="d"
nodeid="${nodebase}1"
nodetoken=abc123
nodeauth="$org/$nodeid:$nodetoken"

nodeagrbase="${namebase}-node-agr"   # agreement ids must be unique
nodeagrid="${nodeagrbase}1"

agbotbase="a"
agbotid="${agbotbase}1"
agbottoken=abcdef
agbotauth="$org/$agbotid:$agbottoken"

agbotagrbase="${namebase}-agbot-agr"   # agreement ids must be unique
agbotagrid="${agbotagrbase}1"

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

curlBasicArgs="-sS -w %{http_code} --output $EX_PERF_DEBUG_FILE $accept"
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
    numtimes=$1
    auth=$2
    url=$3
	echo "Running GET ($auth) $url $numtimes times:"
	start=`date +%s`
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		rc=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$url)
		checkrc $rc 200 '' "GET $url"
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
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
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
	    if [[ "$urlbase" =~ /services$ ]]; then
	        # special case for creating services, because the body needs to be incremented
            # echo curl -X $method $curlBasicArgs $content $auth -d "${body}$i\"}" $EX_URL_ROOT/v1/$urlbase
            rc=$(curl -X $method $curlBasicArgs $content $auth -d "${body}$i\"}" $EX_URL_ROOT/v1/$urlbase)
        else
            # echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$urlbase$i
            rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$urlbase$i)
        fi
		checkrc $rc 201 '' "$method $urlbase$i"
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST/PATCH, numtimes, auth, url, body
function curlputpost {
    method=$1
    numtimes=$2
    auth=$3
    url=$4
    body="$5"
	echo "Running $method ($auth) $url $numtimes times:"
	start=`date +%s`
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		if [[ $body == "" ]]; then
			rc=$(curl -X $method $curlBasicArgs $auth $EX_URL_ROOT/v1/$url)
		else
			# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$url
			rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$url)
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
    for (( i=1 ; i<=$numtimes ; i++ )) ; do
        echo curl -X DELETE $curlBasicArgs $auth $EX_URL_ROOT/v1/$urlbase$i
        rc=$(curl -X DELETE $curlBasicArgs $auth $EX_URL_ROOT/v1/$urlbase$i)
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
	msgNums='1 2'
	msgNums=$(curl -X GET -sS $accept -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase | jq '.messages[].msgId')
	# echo "msgNums: $msgNums"
    checkexitcode $? "GET msg numbers"
    if [[ msgNums == "" ]]; then echo "=======================================> GET msgs returned no output."; fi
    bignum=$(($bignum+1))

	for i in $msgNums; do
		# echo curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase/$i
		rc=$(curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase/$i)
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
    rc=$(curl -X $method $curlBasicArgs $auth $EX_URL_ROOT/v1/$url)
    checkrc $rc 201 '' "$method $url"
    bignum=$(($bignum+1))
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=1, each=${total}s"
}

bignum=0
bigstart=`date +%s`

# Get rid of anything left over from a previous run and then create the org
curldelete 1 "$rootauth" "orgs/$orgbase" "NOT_FOUND_OK"
curladmin "POST" "$rootauth" "admin/clearauthcaches"  # needed to avoid an obscure bug: in the prev run the ibm auth cache was populated and user created, but this run when the org and user are deleted, if the cache entry has not expired the user will not get recreated
curlcreate "POST" 1 "$rootauth" "orgs/$orgbase" '{ "label": "perf test org", "description": "blah blah", "orgType":"IBM", "tags": { "ibmcloud_id": "'$EXCHANGE_IAM_ACCOUNT_ID'" } }'

# Users =================================================

# Put (create) users u*
curlcreate "POST" $EX_NUM_USERS "$rootauth" "orgs/$org/users/$locuserbase" '{"password": "'$pw'", "admin": true, "email": "'$locuser'@gmail.com"}'

# Put (update) users/u1
curlputpost "PUT" $EX_PERF_REPEAT $locuserauth "orgs/$org/users/$locuser" '{"password": "'$pw'", "admin": true, "email": "'$locuser'@gmail.com"}'

# Get users/u1
curlget $EX_PERF_REPEAT $locuserauth "orgs/$org/users/$locuser"

# Post users/u1/confirm
curlputpost "POST" $EX_PERF_REPEAT $locuserauth "orgs/$org/users/$locuser/confirm"

# Get cloud user
curlget $EX_PERF_REPEAT $userauth "orgs/$org/users/iamapikey"

# Get all users - doing this as local user because it has admin authority
curlget $EX_PERF_REPEAT $locuserauth "orgs/$org/users"

# Services =================================================

# Post (create) services ${svcidbase}*
curlcreate "POST" $EX_NUM_SVCS $userauth "orgs/$org/services" '{"label": "svc", "public": true, "url": "'$svcurl'", "version": "'$svcversion'", "sharable": "singleton",
  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a", "arch": "'$svcarchbase    # the ending '" }' will be added by curlcreate

# Put (update) services/${svdid}1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "orgs/$org/services/$svcid" '{"label": "svc", "public": true, "url": "'$svcurl'", "version": "'$svcversion'", "arch": "'$svcarch'", "sharable": "singleton",
  "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a" }'

# Put (update) services/${svdid}1/policy
curlputpost "PUT" $EX_PERF_REPEAT $userauth "orgs/$org/services/$svcid/policy" '{ "properties": [{"name":"purpose", "value":"location", "type":"string"}], "constraints":["a == b"] }'

# Get all services
curlget $EX_PERF_REPEAT $userauth "orgs/$org/services"

# Get services/${svdid}1
curlget $EX_PERF_REPEAT $userauth "orgs/$org/services/$svcid"

# Patterns =================================================

# Post (create) patterns p*
curlcreate "POST" $EX_NUM_PATTERNS $userauth "orgs/$org/patterns/$patternbase" '{"label": "pat", "public": true, "services": [{ "serviceUrl": "'$svcurl'", "serviceOrgid": "'$org'", "serviceArch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] }],
  "userInput": [{
      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [{ "name": "VERBOSE", "value": true }]
  }]
}'

# Put (update) pattern p1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "orgs/$org/patterns/$patternid" '{"label": "pat", "public": true, "services": [{ "serviceUrl": "'$svcurl'", "serviceOrgid": "'$org'", "serviceArch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] }],
  "userInput": [{
      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [{ "name": "VERBOSE", "value": true }]
  }]
}'

# Get all patterns
curlget $EX_PERF_REPEAT $userauth "orgs/$org/patterns"

# Get pattern p1
curlget $EX_PERF_REPEAT $userauth "orgs/$org/patterns/$patternid"

# Business Policies =================================================

# Post (create) business policies bp*
curlcreate "POST" $EX_NUM_BUSINESSPOLICIES $userauth "orgs/$org/business/policies/$buspolbase" '{"label": "buspol", "service": { "name": "'$svcurl'", "org": "'$org'", "arch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] },
  "userInput": [{
      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [{ "name": "VERBOSE", "value": true }]
  }],
  "properties": [{"name":"purpose", "value":"location", "type":"string"}],
  "constraints":["a == b"]
}'

# Put (update) business policy bp1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "orgs/$org/business/policies/$buspolid" '{"label": "buspol", "service": { "name": "'$svcurl'", "org": "'$org'", "arch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] },
  "userInput": [{
      "serviceOrgid": "'$org'", "serviceUrl": "'$svcurl'", "serviceArch": "", "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [{ "name": "VERBOSE", "value": true }]
  }],
  "properties": [{"name":"purpose", "value":"location", "type":"string"}],
  "constraints":["a == b"]
}'

# Get all business policies
curlget $EX_PERF_REPEAT $userauth "orgs/$org/business/policies"

# Get business policy bp1
curlget $EX_PERF_REPEAT $userauth "orgs/$org/business/policies/$buspolid"

# Nodes =================================================

# Put (create) node d*
curlcreate "PUT" $EX_NUM_NODES $userauth "orgs/$org/nodes/$nodebase" '{"token": "'$nodetoken'", "name": "pi", "pattern": "'$org'/'$patternid'", "arch": "'$svcarch'", "registeredServices": [{"url": "'$org'/'$svcurl'", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "'$svcarch'", "propType": "string", "op": "in"},{"name": "version", "value": "1.0.0", "propType": "version", "op": "in"}]}], "publicKey": "ABC"}'

# Put (update) node/d1
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid" '{"token": "'$nodetoken'", "name": "pi", "pattern": "'$org'/'$patternid'", "arch": "'$svcarch'", "registeredServices": [{"url": "'$org'/'$svcurl'", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "'$svcarch'", "propType": "string", "op": "in"},{"name": "version", "value": "1.0.0", "propType": "version", "op": "in"}]}], "publicKey": "ABC"}'

# Put (update) node/d1/policy
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/policy" '{ "properties": [{"name":"purpose", "value":"testing", "type":"string"}], "constraints":["a == b"] }'

# Put (update) node/d1/status
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/status" '{ "connectivity": {"firmware.bluehorizon.network": true}, "services": [] }'

# Get all node
curlget $EX_PERF_REPEAT $userauth "orgs/$org/nodes"

# Get node/d1
curlget $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid"

# Post node/d1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/heartbeat"

# Node Searches =================================================

# Search nodes for pattern p1
curlputpost "POST" $EX_PERF_REPEAT $userauth "orgs/$org/patterns/$patternid/search" '{ "serviceUrl": "'$org'/'$svcurl'", "secondsStale": 10000, "startIndex": 0, "numEntries": 0 }'

# Patch node/d1 to clear pattern, for business policy search
curlputpost "PATCH" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid" '{ "pattern": "" }'

# Search nodes for business policy bp1
curlputpost "POST" $EX_PERF_REPEAT $userauth "orgs/$org/business/policies/$buspolid/search" '{ "changedSince": 0, "startIndex": 0, "numEntries": 0 }'

# Agbots =================================================

# Put (create) agbots a*
curlcreate "PUT" $EX_NUM_AGBOTS $userauth "orgs/$org/agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'

# Put (update) agbots/a1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'

# Get all agbots
curlget $EX_PERF_REPEAT $userauth "orgs/$org/agbots"

# Get agbots/a1
curlget $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid"

# Post agbots/a1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid/heartbeat"

#todo: Post search/node
#curlputpost "POST" $EX_PERF_REPEAT $agbotauth "orgs/$org/search/nodes" '{"registeredServices": [{"url": "'$org'/'$svcurl'", "properties": [{"name": "arch", "value": "'$svcarch'", "propType": "wildcard", "op": "in"}, {"name": "version", "value": "*", "propType": "version", "op": "in"}]}], "secondsStale": 0, "startIndex": 0, "numEntries": 0}'

# Agreements =================================================

# Put (create) node d1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $nodeauth "orgs/$org/nodes/$nodeid/agreements/$nodeagrbase" '{"services": [], "agreementService": {"orgid": "'$org'", "pattern": "'$org'/'$patternid'", "url": "'$org'/'$svcurl'"}, "state": "negotiating"}'

# Put (update) node d1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/agreements/$nodeagrid" '{"services": [], "agreementService": {"orgid": "'$org'", "pattern": "'$org'/'$patternid'", "url": "'$org'/'$svcurl'"}, "state": "negotiating"}'

# Get all node d1 agreements
curlget $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/agreements"

# Get node d1 agreement agr1
curlget $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/agreements/$nodeagrid"

# Put (create) agbot a1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $agbotauth "orgs/$org/agbots/$agbotid/agreements/$agbotagrbase" '{"service": {"orgid": "'$org'", "pattern": "'$org'/'$patternid'", "url": "'$org'/'$svcurl'"}, "state": "negotiating"}'

# Put (update) agbot a1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid/agreements/$agbotagrid" '{"service": {"orgid": "'$org'", "pattern": "'$org'/'$patternid'", "url": "'$org'/'$svcurl'"}, "state": "negotiating"}'

# Get all agbot a1 agreements
curlget $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid/agreements"

# Get agbot a1 agreement agr1
curlget $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid/agreements/$agbotagrid"

# Msgs =================================================

# Post (create) node d1 msgs
curlputpost "POST" $EX_NUM_MSGS $agbotauth "orgs/$org/nodes/$nodeid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all node d1 msgs
curlget $EX_PERF_REPEAT $nodeauth "orgs/$org/nodes/$nodeid/msgs"

# Post (create) agbot a1 msgs
curlputpost "POST" $EX_NUM_MSGS $nodeauth "orgs/$org/agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all agbot a1 msgs
curlget $EX_PERF_REPEAT $agbotauth "orgs/$org/agbots/$agbotid/msgs"

# Admin =================================================

# Post admin/version
curlget $EX_PERF_REPEAT $userauth admin/version

# Post admin/status
curlget $EX_PERF_REPEAT $userauth admin/status

# Start deleting everything ===========================================

# Delete node d1 msgs
deletemsgs $nodeauth "orgs/$org/nodes/$nodeid/msgs"

# Delete agbot a1 msgs
deletemsgs $agbotauth "orgs/$org/agbots/$agbotid/msgs"

# Delete node d1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $nodeauth "orgs/$org/nodes/$nodeid/agreements/$nodeagrbase"

# Delete agbot a1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $agbotauth "orgs/$org/agbots/$agbotid/agreements/$agbotagrbase"

# Delete node d*
curldelete $EX_NUM_NODES $userauth "orgs/$org/nodes/$nodebase"

# Delete agbot a*
curldelete $EX_NUM_AGBOTS $userauth "orgs/$org/agbots/$agbotbase"

# Delete patterns p*
curldelete $EX_NUM_PATTERNS $userauth "orgs/$org/patterns/$patternbase"

# Delete services ${svcidbase}*
curldelete $EX_NUM_SVCS $userauth "orgs/$org/services/$svcidbase"

# Delete users u*
curldelete $EX_NUM_USERS $rootauth "orgs/$org/users/$locuserbase"

# Delete org to make sure everything is completely gone
curldelete 1 $rootauth "orgs/$orgbase"

bigtotal=$(($(date +%s)-bigstart))
sumMsg="Overall: total=${bigtotal}s, num=$bignum, each=$(divide $bigtotal $bignum)s"
echo "$sumMsg" > $EX_PERF_REPORT_FILE
echo "$sumMsg"
