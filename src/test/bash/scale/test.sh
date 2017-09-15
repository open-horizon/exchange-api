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

# Test configuration. You can override these before invoking the script, if you want.
EX_NUM_USERS="${EX_NUM_USERS:-20}"
EX_NUM_node="${EX_NUM_node:-40}"
EX_NUM_AGBOTS="${EX_NUM_AGBOTS:-20}"
EX_NUM_AGREEMENTS="${EX_NUM_AGREEMENTS:-40}"
EX_NUM_MSGS="${EX_NUM_MSGS:-40}"
EX_NUM_BCTYPES="${EX_NUM_BCTYPES:-20}"
EX_NUM_BLOCKCHAINS="${EX_NUM_BLOCKCHAINS:-20}"
EX_PERF_REPEAT="${EX_PERF_REPEAT:-30}"
EX_URL_ROOT="${EXCHANGE_URL_ROOT:-http://localhost:8080}"
EX_ROOT_PW="${EXCHANGE_ROOTPW:-rootpw}"	# this has to match what is in the exchange config.json

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

# rootpw="Horizon-Rul3s"      # it is a precondition of testing that they put this root pw in config.json
rootauth="root:$EX_ROOT_PW"

userbase="${namebase}u"
user="${userbase}1"
pw=pw
userauth="$user:$pw"

nodebase="${namebase}d"
nodeid="${nodebase}1"
nodetoken=abc123
nodeauth="$nodeid:$nodetoken"

agbotbase="${namebase}a"
agbotid="${agbotbase}1"
agbottoken=abcdef
agbotauth="$agbotid:$agbottoken"

agreementbase="${namebase}agr"
agreementid="${agreementbase}1"

bctypebase="${namebase}bt"
bctypeid="${bctypebase}1"

blockchainbase="${namebase}bc"
blockchainid="${blockchainbase}1"

curlBasicArgs="-s -w %{http_code} --output /dev/null $accept"
# curlBasicArgs="-s -w %{http_code} $accept"
# curlBasicArgs="-s -f"
# set -x

# Check the http code returned by curl. Args: returned rc, good rc, second good rc (optional)
function checkrc {
    if [[ $1 != $2 && ( -z $3 ||  $1 != $3 ) ]]; then
	    httpcode="${1:0:3}"     # when an error occurs with curl the rest method output comes in stderr with the http code
		echo "=======================================> curl failed with: $1, exiting."
		exit $httpcode
	fi
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 != 0 ]]; then
		echo "=======================================> commands failed with exit code $1, exiting."
		exit $1
	fi
}

function divide {
	bc <<< "scale=3; $1/$2"
}

# set -x
# curl -X GET $curlBasicArgs -H "Authorization:Basic$userauth" $EX_URL_ROOT/v1/node
# exit

# Args: numtimes, auth, url
function curlget {
    numtimes=$1
    auth=$2
    url=$3
	echo "Running GET ($auth) $url $numtimes times:"
	start=`date +%s`
	for (( i=1 ; i<=$numtimes ; i++ )) ; do
		rc=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$url)
		checkrc $rc 200
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST, numtimes, auth, url-base, body
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
		# echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$urlbase$i
		rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EX_URL_ROOT/v1/$urlbase$i)
		checkrc $rc 201
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: PUT/POST, numtimes, auth, url, body
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
		checkrc $rc 201
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

# Args: numtimes, auth, url-base, NOT_FOUND_OK (optional)
function curldelete {
    numtimes=$1
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
		# echo curl -X DELETE $curlBasicArgs $auth $EX_URL_ROOT/v1/$urlbase$i
		rc=$(curl -X DELETE $curlBasicArgs $auth $EX_URL_ROOT/v1/$urlbase$i)
		checkrc $rc 204 $secondcode
		echo -n .
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
	msgNums=$(curl -X GET -s $accept -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase | jq '.messages[].msgId')
	# echo "msgNums: $msgNums"
    checkexitcode $?
    if [[ msgNums == "" ]]; then echo "=======================================> GET msgs returned no output."; fi
    bignum=$(($bignum+1))

	for i in $msgNums; do
		# echo curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase/$i
		rc=$(curl -X DELETE $curlBasicArgs -H "Authorization:Basic $auth" $EX_URL_ROOT/v1/$urlbase/$i)
		checkrc $rc 204
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$numtimes, each=$(divide $total $numtimes)s"
}

bignum=0
bigstart=`date +%s`

# Get rid of anything left over from a previous run
curldelete $EX_NUM_USERS $rootauth "users/$userbase" "NOT_FOUND_OK"

# testings when adding new rest methods...
#curlcreate "POST" 1 "" "users/$userbase" '{"password": "pw", "email": "foo@gmail.com"}'
#curlcreate "PUT" 1 $userauth "node/$nodebase" '{"token": "'$nodetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-node-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'
#curlcreate "PUT" 1 $userauth "agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id", "publicKey": "ABC"}'
#curlputpost "POST" 2 $agbotauth "node/$nodeid/msgs" '{"message": "hey there", "ttl": 300}'
#curlget 1 $nodeauth "node/$nodeid/msgs"
#deletemsgs $nodeauth "node/$nodeid/msgs"
#curlputpost "POST" 2 $nodeauth "agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'
#curlget 1 $agbotauth "agbots/$agbotid/msgs"
#deletemsgs $agbotauth "agbots/$agbotid/msgs"
#curlcreate "PUT" 1 $userauth "bctypes/$bctypebase" '{"description": "abc", "details": "escaped json"}'
#curlputpost "PUT" 1 $userauth "bctypes/$bctypeid" '{"description": "abc", "details": "escaped json"}'
#curlget 1 $nodeauth bctypes
#curlget 1 $nodeauth bctypes/$bctypeid
#curlcreate "PUT" 1 $userauth "bctypes/$bctypeid/blockchains/$blockchainbase" '{"description": "abc", "details": "escaped json"}'
#curlputpost "PUT" 1 $userauth "bctypes/$bctypeid/blockchains/$blockchainid" '{"description": "abc", "details": "escaped json"}'
#curlget 1 $nodeauth "bctypes/$bctypeid/blockchains"
#curlget 1 $nodeauth "bctypes/$bctypeid/blockchains/$blockchainid"
#exit

# Users =================================================

# Put (create) users u*
curlcreate "POST" $EX_NUM_USERS "" "users/$userbase" '{"password": "pw", "email": "foo@gmail.com"}'

# Put (update) users/u1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "users/$user" '{"password": "'$pw'", "email": "'$user'@gmail.com"}'

# Get all users
curlget $EX_PERF_REPEAT $rootauth "users"

# Get users/u1
curlget $EX_PERF_REPEAT $userauth "users/$user"

# Post users/u1/confirm
curlputpost "POST" $EX_PERF_REPEAT $userauth users/$user/confirm

# node =================================================

# Put (create) node d*
curlcreate "PUT" $EX_NUM_node $userauth "node/$nodebase" '{"token": "'$nodetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-node-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'

# Put (update) node/d1
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "node/$nodeid" '{"token": "'$nodetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-node-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'

# Get all node
curlget $EX_PERF_REPEAT $userauth node

# Get node/d1
curlget $EX_PERF_REPEAT $nodeauth node/$nodeid

# Post node/d1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $nodeauth node/$nodeid/heartbeat

# Agbots =================================================

# Put (create) agbots a*
curlcreate "PUT" $EX_NUM_AGBOTS $userauth "agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id", "publicKey": "ABC"}'

# Put (update) agbots/a1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id", "publicKey": "ABC"}'

# Get all agbots
curlget $EX_PERF_REPEAT $userauth agbots

# Get agbots/a1
curlget $EX_PERF_REPEAT $agbotauth agbots/$agbotid

# Post agbots/a1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $agbotauth agbots/$agbotid/heartbeat

# Post search/node
curlputpost "POST" $EX_PERF_REPEAT $agbotauth search/node '{"desiredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-node-api", "properties": [{"name": "arch", "value": "arm", "propType": "wildcard", "op": "in"}, {"name": "version", "value": "*", "propType": "version", "op": "in"}]}], "secondsStale": 0, "propertiesToReturn": ["string"], "startIndex": 0, "numEntries": 0}'

# Agreements =================================================

# Put (create) node d1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $nodeauth "node/$nodeid/agreements/$agreementbase" '{"microservice": "sdr", "state": "negotiating"}'

# Put (update) node d1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $nodeauth "node/$nodeid/agreements/$agreementid" '{"microservice": "sdr", "state": "negotiating"}'

# Get all node d1 agreements
curlget $EX_PERF_REPEAT $nodeauth "node/$nodeid/agreements"

# Get node d1 agreement agr1
curlget $EX_PERF_REPEAT $nodeauth "node/$nodeid/agreements/$agreementid"

# Put (create) agbot a1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $agbotauth "agbots/$agbotid/agreements/$agreementbase" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Put (update) agbot a1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Get all agbot a1 agreements
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements"

# Get agbot a1 agreement agr1
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid"

# Msgs =================================================

# Post (create) node d1 msgs
curlputpost "POST" $EX_NUM_MSGS $agbotauth "node/$nodeid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all node d1 msgs
curlget $EX_PERF_REPEAT $nodeauth "node/$nodeid/msgs"

# Post (create) agbot a1 msgs
curlputpost "POST" $EX_NUM_MSGS $nodeauth "agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all agbot a1 msgs
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/msgs"

# Bctypes =================================================

# Put (create) bctypes bt*
curlcreate "PUT" $EX_NUM_BCTYPES $userauth "bctypes/$bctypebase" '{"description": "abc", "details": "escaped json"}'

# Put (update) bctypes/bt1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "bctypes/$bctypeid" '{"description": "abc", "details": "escaped json"}'

# Get all bctypes
curlget $EX_PERF_REPEAT $nodeauth bctypes

# Get bctypes/d1
curlget $EX_PERF_REPEAT $nodeauth bctypes/$bctypeid

# Blockchains =================================================

# Put (create) blockchains bc*
curlcreate "PUT" $EX_NUM_BLOCKCHAINS $userauth "bctypes/$bctypeid/blockchains/$blockchainbase" '{"description": "abc", "details": "escaped json"}'

# Put (update) bctype bt1 blockchain bc1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "bctypes/$bctypeid/blockchains/$blockchainid" '{"description": "abc", "details": "escaped json"}'

# Get all bctype bt1 blockchains
curlget $EX_PERF_REPEAT $nodeauth "bctypes/$bctypeid/blockchains"

# Get bctype bt1 blockchain bc1
curlget $EX_PERF_REPEAT $nodeauth "bctypes/$bctypeid/blockchains/$blockchainid"

# Admin =================================================

# Post admin/hashpw
curlputpost "POST" $EX_PERF_REPEAT $rootauth admin/hashpw '{"password": "somepw"}'

# Post admin/reload
curlputpost "POST" $EX_PERF_REPEAT $rootauth admin/reload

# Start deleting everything ===========================================

# Delete blockchains bc*
curldelete $EX_NUM_BLOCKCHAINS $userauth "bctypes/$bctypeid/blockchains/$blockchainbase"

# Delete bctypes bt*
curldelete $EX_NUM_BCTYPES $userauth "bctypes/$bctypebase"

# Delete node d1 msgs
deletemsgs $nodeauth "node/$nodeid/msgs"

# Delete agbot a1 msgs
deletemsgs $agbotauth "agbots/$agbotid/msgs"

# Delete node d1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $nodeauth "node/$nodeid/agreements/$agreementbase"

# Delete agbot a1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $agbotauth "agbots/$agbotid/agreements/$agreementbase"

# Delete node d*
curldelete $EX_NUM_node $userauth "node/$nodebase"

# Delete agbot a*
curldelete $EX_NUM_AGBOTS $userauth "agbots/$agbotbase"

# Delete users u*
curldelete $EX_NUM_USERS $rootauth "users/$userbase"

bigtotal=$(($(date +%s)-bigstart))
echo "Overall: total=${bigtotal}s, num=$bignum, each=$(divide $bigtotal $bignum)s"