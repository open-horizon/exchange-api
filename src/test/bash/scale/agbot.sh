#!/bin/bash

# Performance test simulating many agbots making calls to the exchange. For scale testing, run many instances of this using wrapper.sh

if [[ $1 == "-h" || $1 == "--help" ]]; then
	echo "Usage: $0 [<name base>]"
	exit 0
fi

namebase="${1:-1}-agbot"

# These env vars are required
if [[ -z "$EXCHANGE_ROOTPW" || -z "$EXCHANGE_IAM_KEY" || -z "$EXCHANGE_IAM_EMAIL" ]]; then
    echo "Error: environment variables EXCHANGE_ROOTPW, EXCHANGE_IAM_KEY (platform API key for your cloud user), EXCHANGE_IAM_EMAIL must all be set."
    exit 1
fi
# Setting EXCHANGE_IAM_ACCOUNT_ID (id of your cloud account) distinguishes this as an ibm public cloud environment, instead of ICP

scriptName="$(basename $0)"

# Test configuration. You can override these before invoking the script, if you want.
#HZN_EXCHANGE_URL="${HZN_EXCHANGE_URL:-http://localhost:8080/v1}"  # <- do not default this to localhost
if [[ -z "$HZN_EXCHANGE_URL" ]]; then
    # try to get it from /etc/default/horizon
    if [[ -f '/etc/default/horizon' ]]; then
        source /etc/default/horizon
        if [[ -z "$HZN_EXCHANGE_URL" ]]; then
            echo "Error: HZN_EXCHANGE_URL must be set in the environment or in /etc/default/horizon"
            exit 1
        fi
        export HZN_EXCHANGE_URL
    else
        echo "Error: HZN_EXCHANGE_URL must be set in the environment or in /etc/default/horizon"
        exit 1
    fi
fi

EX_PERF_ORG="${EX_PERF_ORG:-performancenodeagbot}"

# default of where to write the summary or error msgs. Can be overridden
EX_PERF_REPORT_DIR="${EX_PERF_REPORT_DIR:-/tmp/exchangePerf}"
reportDir="$EX_PERF_REPORT_DIR/$scriptName"
EX_PERF_REPORT_FILE="${EX_PERF_REPORT_FILE:-$reportDir/$namebase.summary}"
# this file holds the output of the most recent curl cmd, which is useful when the script errors out
EX_PERF_DEBUG_FILE="${EX_PERF_DEBUG_FILE:-$reportDir/debug/$namebase.lastmsg}"

# The length of the performance test, measured in the number of times each agbot checks for agreements (by default 10 sec each)
numAgrChecks="${EX_PERF_NUM_AGR_CHECKS:-90}"
# On average each rest api takes 0.4 seconds, which means an agbot's repeated apis would take ???? seconds, so this script can do that ??? times in the 60 s interval,
# thereby representing the load on the exchange of about ?? agbots
numAgbots="${EX_PERF_NUM_AGBOTS:-1}"
# An estimate of the average number of msgs a agbot will have in flight at 1 time
numMsgs="${EX_PERF_NUM_MSGS:-10}"

# These defaults are taken from /etc/horizon/anax.json
# "NewContractIntervalS": 10, (gets patterns and policies, and do both /search apis)
# "ProcessGovernanceIntervalS": 10, (decide if existing agreements should continue, nodehealth, etc.)
# "ExchangeVersionCheckIntervalM": 1, (60 sec)
# "ExchangeHeartbeat": 60,
# "ActiveDeviceTimeoutS": 180, <- maybe not relevant
# AgreementBot.CheckUpdatedPolicyS: 15 (check for updated policies)
# AgreementTimeoutS
newAgreementInterval="${EX_AGBOT_NEW_AGR_INTERVAL:-10}"
processGovInterval="${EX_AGBOT_PROC_GOV_INTERVAL:-10}"
agbotHbInterval="${EX_AGBOT_HB_INTERVAL:-60}"
versionCheckInterval="${EX_AGBOT_VERSION_CHECK_INTERVAL:-60}"
#activeNodeTimeout="${EX_AGBOT_ACTIVE_NODE_CHECK:-180}"
# EX_PERF_NO_SLEEP can be set to disable sleep if it finishes an interval early

# CURL_CA_BUNDLE can be exported in our parent if a self-signed cert is needed.

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EXCHANGE_ROOTPW"


# This script will create just 1 org and put everything else under that. If you use wrapper.sh, all instances of this script and agbot.sh should use the same org.
orgbase="$EX_PERF_ORG"
org="${orgbase}"

# Test with a cloud user.
if [[ -n "$EXCHANGE_IAM_ACCOUNT_ID" ]]; then
    userauth="$org/iamapikey:$EXCHANGE_IAM_KEY"
else
    # for ICP we can't play the game of having our own org, but associating it with another account, so we have to create and use a local exchange user
    userauth="$org/$EXCHANGE_IAM_EMAIL:$EXCHANGE_IAM_KEY"
fi

# this node id can not conflict with the nodes that node.sh creates
nodebase="${namebase}-n"
nodeid="${nodebase}1"
nodetoken=abc123
nodeauth="$org/$nodeid:$nodetoken"

agbotbase="${namebase}-a"
agbotid="${agbotbase}1"
agbottoken=abcdef
agbotauth="$org/$agbotid:$agbottoken"

#svcurlbase="${namebase}-svcurl"
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
		if [[ "$5" == 'continue' ]]; then
		    nextAction='continuing'
		else
		    nextAction='exiting'
		fi
		errMsg="===============> command $2 failed with exit code $1, $nextAction."
		echo "$errMsg" >> $EX_PERF_REPORT_FILE
		echo "$errMsg" >&2
		if [[ "$3" != 'continue' ]]; then exit $1; fi
	fi
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
    local words
    words=( $1 )
    echo ${#words[@]}
    #wc -w <<< "$1"
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

# Can not delete the org in case other instances of this script are using it. Whoever calls this script must delete it.
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


# let node.sh create the services, patterns, and business policies

# Put (create) agbots a*
curlcreate "PUT" $numAgbots $userauth "orgs/$org/agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'

# Add (create) agbot pattern orgs. We don't have multiple orgs so this just simulates the ICP case
for (( i=1 ; i<=$numAgbots ; i++ )) ; do
    curlcreateone "POST" $userauth "orgs/$org/agbots/$agbotbase$i/patterns" '{"patternOrgid": "'$org'", "pattern": "*", "nodeOrgid": "'$org'"}'
    curlcreateone "POST" $userauth "orgs/$org/agbots/$agbotbase$i/patterns" '{"patternOrgid": "IBM", "pattern": "*", "nodeOrgid": "'$org'"}'
done

# Put (create) 1 svc, pattern, and node to be able to create agbot msgs, and to have pattern search return at least 1 node
curlcreateone "POST" $userauth "orgs/$org/services" '{"label": "svc", "public": true, "url": "'$svcurl'", "version": "'$svcversion'", "arch": "'$svcarch'", "sharable": "singleton", "deployment": "{\"services\":{\"svc\":{\"image\":\"openhorizon/gps:1.2.3\"}}}", "deploymentSignature": "a" }' 403
curlcreateone "POST" $userauth "orgs/$org/patterns/$patternid" '{"label": "pat", "public": false, "services": [{ "serviceUrl": "'$svcurl'", "serviceOrgid": "'$org'", "serviceArch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] }] }'
curlcreateone "PUT" $userauth "orgs/$org/nodes/$nodeid" '{"token": "'$nodetoken'", "name": "pi", "pattern": "'$org'/'$patternid'", "publicKey": "ABC"}'

# Post (create) agbot a* msgs
for (( i=1 ; i<=$numAgbots ; i++ )) ; do
    curlputpostmulti "POST" $numMsgs $nodeauth "orgs/$org/agbots/$agbotbase$i/msgs" '{"message": "hey there", "ttl": 8640000}'   # ttl is 2400 hours
done

#todo: add policy calls


#=========== Loop thru repeated exchange calls =================================================

# The repeated rest apis a agbot runs are:
#   GET /orgs/<org>/agbots/<agbot>/msgs
#   POST /orgs/<org>/patterns/<pattern>/search (for each pattern)
#   POST /orgs/<org>/business/policies/<policy>/search
#   GET /orgs/<org>/agbots/<agbot>/patterns
#   GET /orgs/<org>
#   GET /orgs/<org>/business/policies
#   GET /orgs/<org>/services/<service>/policy
#   GET /orgs/<org>/patterns
#   GET /orgs/<org>/nodes/<node>
#   POST /orgs/<org>/business/policies/<policy>/search
#   GET /orgs/<org>/services
#   GET /orgs/<org>/services/<service>/policy
#   POST /orgs/<org>/business/policies/<policy>/search
#   GET /orgs/<org>/services/<service>/policy
#   GET /orgs/<org>/services
#   GET /orgs/<org>/nodes/<node>
#   POST /orgs/<org>/patterns/<pattern>/nodehealth
#   POST /orgs/<org>/patterns/<pattern>/search
#   GET /orgs/<org>/agbots/<agbot>/patterns
#   GET /orgs/<org>
#   GET /orgs/<org>/agbots/<agbot>/businesspols
#   GET /orgs/<org>/business/policies
#   GET /orgs/<org>/services/<service>/policy
#   GET /orgs/<org>/agbots/<agbot>/patterns
#   GET /orgs/<org>/patterns
#   GET /orgs/<org>/agbots/<agbot>/businesspols
#   GET /orgs/<org>/business/policies

#   GET /admin/version (every 60 s)

#   POST /orgs/<org>/agbots/<agbot>/heartbeat (every 60 s)
#   GET /orgs/<org>/agbots/<agbot>

printf "\nRunning $numAgrChecks agreement checks for $numAgbots agbots:\n"
agbotHbCount=0
versionCheckCount=0
patsMaxProcessed=0
nodesProcessed=0
nodesMinProcessed=100000
nodesMaxProcessed=0
nodesLastProcessed=0
iterDeltaTotal=0
# Note: the default value of newAgreementInterval and processGovInterval are the same, so for now we assume they are the same value

for (( h=1 ; h<=$numAgrChecks ; h++ )) ; do
    echo "Agbot agreement check $h"
	startIteration=`date +%s`
    # We assume 1 agreement check of all the agbots takes newAgreementInterval seconds, so increment our other counts by that much
    agbotHbCount=$(( $agbotHbCount + $newAgreementInterval ))
    versionCheckCount=$(( $versionCheckCount + $newAgreementInterval ))

    for (( a=1 ; a<=$numAgbots ; a++ )) ; do
        echo "Agbot $a"
        myagbotauth="$org/$agbotbase$a:$agbottoken"

        # we don't actually use this info, but the agbots query it, so we should
        curlget $myagbotauth "orgs/$org/agbots/$agbotbase$a/patterns"
        curlget $myagbotauth "orgs/$org"
        curlget $rootauth "orgs/IBM"

        # These api methods are run every agreement check and process governance:
        # Get the patterns in the org and do a search for each one. Note: we are only getting the patterns in our org, because the number of patterns in the IBM org will be small in comparison.
        url="orgs/$org/patterns"
        if [[ -n "$VERBOSE" ]]; then echo "Running GET ($myagbotauth) $url"; fi
        output=$(curl -X GET -sS -w '%{http_code}' $accept -H "Authorization:Basic $myagbotauth" $certFile $HZN_EXCHANGE_URL/$url)
        #echo "DEBUG: output: $output."
        httpcode=${output:$((${#output}-3))}    # the last 3 chars are the http code
        patterns="${output%[0-9][0-9][0-9]}"   # for the output, get all but the 3 digits of http code
        #echo "DEBUG: httpcode: $httpcode, patterns: $patterns."
		checkhttpcode $httpcode '200 404' "GET $url, output: $patterns" 'continue'
        if [[ "$httpcode" == "200" ]]; then
            patterns=$(jq -r '.patterns | keys[]' <<< "$patterns")
            #echo "DEBUG: patterns: $patterns."
            if [[ "$patterns" != "" ]]; then
                numPatterns=$(wordCount "$patterns")
                echo "Processing $numPatterns patterns"
                patsMaxProcessed=$(max $patsMaxProcessed $numPatterns )
                numAgrChkNodes=0
                for p in $patterns; do
                    # the pattern ids are returned to us with the org prepended, strip that
                    pat=${p#*/}
                    if [[ -n "$VERBOSE" ]]; then echo "Pattern $pat"; fi

                    # run nodehealth for this pattern. Not sure yet what to do with this result yet
                    curlputpost "POST" $myagbotauth "orgs/$org/patterns/$pat/nodehealth" '{ "lastTime": "" }' 404   # empty string for lastTime will return all nodes
                    curlget $myagbotauth "orgs/$org/services" 404
                    curlget $myagbotauth "orgs/$org/services/$svcid" 404   # not sure why both of these are called, but they are

                    # Search for nodes with this pattern
                    searchBody='{ "serviceUrl": "'$org'/'$svcurl'", "secondsStale": 0, "startIndex": 0, "numEntries": 0 }'
                    url="orgs/$org/patterns/$pat/search"
                    if [[ -n "$VERBOSE" ]]; then echo "Running POST ($myagbotauth) $url"; fi
                    output=$(curl -X POST -sS -w '%{http_code}' $accept -H "Authorization:Basic $myagbotauth" $certFile $content -d "$searchBody" $HZN_EXCHANGE_URL/$url)
                    httpcode=${output:$((${#output}-3))}    # the last 3 chars are the http code
                    nodes="${output%[0-9][0-9][0-9]}"   # for the output, get all but the 3 digits of http code
                    # http code 400 can occur when a pattern or service was deleted between the time of calling /patterns and /search
                    checkhttpcode $httpcode '201 404 400' "POST $url, output: $nodes" 'continue'
                    #echo "DEBUG: nodes=$nodes."
                    if [[ "$httpcode" == "201" ]]; then
                        nodes=$(jq -r '.nodes[].id' <<< "$nodes")
                        if [[ "$nodes" != "" ]]; then
                            numNodes=$(wordCount "$nodes")
                            nodesProcessed=$(( $nodesProcessed + $numNodes ))
                            numAgrChkNodes=$(( $numAgrChkNodes + $numNodes ))
                            nodesMaxProcessed=$(max $nodesMaxProcessed $numNodes )
                            nodesMinProcessed=$(min $nodesMinProcessed $numNodes )
                            nodesLastProcessed=$numNodes
                            for n in $nodes; do
                                # the node ids are returned to us with the org prepended, strip that
                                nid=${n#*/}
                                if [[ -n "$VERBOSE" ]]; then echo "Node $nid"; fi

                                curlget $myagbotauth "orgs/$org/nodes/$nid"
                                curlputpost "POST" $myagbotauth "orgs/$org/nodes/$nid/msgs" '{"message": "hey there", "ttl": 3000}'
                            done
                        else
                            nodesMinProcessed=0
                        fi
                    fi
                done
                #todo: query agbot businesspols orgs and business policies and do a search for each, and query service policy
                echo "Processed $numAgrChkNodes nodes"
            fi
        fi


        # Get my agbot msgs
        curlget $myagbotauth "orgs/$org/agbots/$agbotbase$a/msgs"


        # If it is time to heartbeat, do that
        if [[ $agbotHbCount -ge $agbotHbInterval ]]; then
            # Post agbot/n1/heartbeat and get my instance
            curlputpost "POST" $myagbotauth "orgs/$org/agbots/$agbotbase$a/heartbeat"
            curlget $myagbotauth "orgs/$org/agbots/$agbotbase$a"
        fi

        # If it is time to do a version check, do that
        if [[ $versionCheckCount -ge $versionCheckInterval ]]; then
            # Get admin/version
            curlget $myagbotauth admin/version
        fi

    done

    # Reset our counters if appropriate
    if [[ $agbotHbCount -ge $agbotHbInterval ]]; then
        agbotHbCount=0
    fi
    if [[ $versionCheckCount -ge $versionCheckInterval ]]; then
        versionCheckCount=0
    fi

    # If we completed this iteration in less than newAgreementInterval (because there were no patterns or nodes yet), sleep the rest of the time
	iterTime=$(($(date +%s)-startIteration))
	iterDelta=$(( $newAgreementInterval - $iterTime ))
	iterDeltaTotal=$(( $iterDeltaTotal + $iterDelta ))
	if [[ $iterDelta -gt 0 && -z "$EX_PERF_NO_SLEEP" ]]; then
	    echo "Sleeping for $iterDelta seconds at the end of agbot agreement check $h of $numAgrChecks because loop iteration finished early"
	    sleep $iterDelta
	fi

done

#=========== Clean up ===========================================

printf "\nCleaning up from test:\n"

# Not necessary, will get deleted when the agbot is deleted: Delete agbot a* msgs
#for (( i=1 ; i<=$numAgbots ; i++ )) ; do
#    deletemsgs $userauth "orgs/$org/agbots/$agbotbase$i/msgs"
#done

# Delete agbot a*
curldelete $numAgbots $userauth "orgs/$org/agbots/$agbotbase"

# Delete the pattern
curldeleteone $userauth "orgs/$org/patterns/$patternid"

# Delete the service
curldeleteone $userauth "orgs/$org/services/$svcid" 404

# Delete node n*
curldeleteone $userauth "orgs/$org/nodes/$nodeid"

# Can not delete the org in case other instances of this script are still using it. Whoever calls this script must delete it.
#curldeleteone $rootauth "orgs/$org"

iterDeltaAvg=$(divide $iterDeltaTotal $numAgrChecks)
nodesProcAvg=$(divide $nodesProcessed $numAgrChecks)
bigtotal=$(($(date +%s)-bigstart))
bigAvg=$(divide $bigtotal $bignum)

sumMsg="Simulated $numAgbots agbots for $numAgrChecks agreement-checks
Max patterns=$patsMaxProcessed, total nodes=$nodesProcessed, avg=$nodesProcAvg nodes/agr-chk
Max nodes=$nodesMaxProcessed, min nodes=$nodesMinProcessed, last nodes=$nodesLastProcessed
Overall: total time=$bigtotal s, num ops=$bignum, avg=$bigAvg s/op, avg iteration delta=$iterDeltaAvg s"

printf "$sumMsg\n" >> $EX_PERF_REPORT_FILE
printf "\n$sumMsg\n"
