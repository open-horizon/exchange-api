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
EX_NUM_DEVICES="${EX_NUM_DEVICES:-40}"
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

devicebase="${namebase}d"
deviceid="${devicebase}1"
devicetoken=abc123
deviceauth="$deviceid:$devicetoken"

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
# curl -X GET $curlBasicArgs -H "Authorization:Basic$userauth" $EX_URL_ROOT/v1/devices
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

# Get all the msgs for this device and delete them 1 by 1
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
#curlcreate "PUT" 1 $userauth "devices/$devicebase" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'
#curlcreate "PUT" 1 $userauth "agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id", "publicKey": "ABC"}'
#curlputpost "POST" 2 $agbotauth "devices/$deviceid/msgs" '{"message": "hey there", "ttl": 300}'
#curlget 1 $deviceauth "devices/$deviceid/msgs"
#deletemsgs $deviceauth "devices/$deviceid/msgs"
#curlputpost "POST" 2 $deviceauth "agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'
#curlget 1 $agbotauth "agbots/$agbotid/msgs"
#deletemsgs $agbotauth "agbots/$agbotid/msgs"
#curlcreate "PUT" 1 $userauth "bctypes/$bctypebase" '{"description": "abc", "details": "escaped json"}'
#curlputpost "PUT" 1 $userauth "bctypes/$bctypeid" '{"description": "abc", "details": "escaped json"}'
#curlget 1 $deviceauth bctypes
#curlget 1 $deviceauth bctypes/$bctypeid
#curlcreate "PUT" 1 $userauth "bctypes/$bctypeid/blockchains/$blockchainbase" '{"description": "abc", "details": "escaped json"}'
#curlputpost "PUT" 1 $userauth "bctypes/$bctypeid/blockchains/$blockchainid" '{"description": "abc", "details": "escaped json"}'
#curlget 1 $deviceauth "bctypes/$bctypeid/blockchains"
#curlget 1 $deviceauth "bctypes/$bctypeid/blockchains/$blockchainid"
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

# Devices =================================================

# Put (create) devices d*
curlcreate "PUT" $EX_NUM_DEVICES $userauth "devices/$devicebase" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'

# Put (update) devices/d1
curlputpost "PUT" $EX_PERF_REPEAT $deviceauth "devices/$deviceid" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'

# Get all devices
curlget $EX_PERF_REPEAT $userauth devices

# Get devices/d1
curlget $EX_PERF_REPEAT $deviceauth devices/$deviceid

# Post devices/d1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $deviceauth devices/$deviceid/heartbeat

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

# Post search/devices
curlputpost "POST" $EX_PERF_REPEAT $agbotauth search/devices '{"desiredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "properties": [{"name": "arch", "value": "arm", "propType": "wildcard", "op": "in"}, {"name": "version", "value": "*", "propType": "version", "op": "in"}]}], "secondsStale": 0, "propertiesToReturn": ["string"], "startIndex": 0, "numEntries": 0}'

# Agreements =================================================

# Put (create) device d1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $deviceauth "devices/$deviceid/agreements/$agreementbase" '{"microservice": "sdr", "state": "negotiating"}'

# Put (update) device d1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements/$agreementid" '{"microservice": "sdr", "state": "negotiating"}'

# Get all device d1 agreements
curlget $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements"

# Get device d1 agreement agr1
curlget $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements/$agreementid"

# Put (create) agbot a1 agreements agr*
curlcreate "PUT" $EX_NUM_AGREEMENTS $agbotauth "agbots/$agbotid/agreements/$agreementbase" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Put (update) agbot a1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Get all agbot a1 agreements
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements"

# Get agbot a1 agreement agr1
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid"

# Msgs =================================================

# Post (create) device d1 msgs
curlputpost "POST" $EX_NUM_MSGS $agbotauth "devices/$deviceid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all device d1 msgs
curlget $EX_PERF_REPEAT $deviceauth "devices/$deviceid/msgs"

# Post (create) agbot a1 msgs
curlputpost "POST" $EX_NUM_MSGS $deviceauth "agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'

# Get all agbot a1 msgs
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/msgs"

# Bctypes =================================================

# Put (create) bctypes bt*
curlcreate "PUT" $EX_NUM_BCTYPES $userauth "bctypes/$bctypebase" '{"description": "abc", "details": "escaped json"}'

# Put (update) bctypes/bt1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "bctypes/$bctypeid" '{"description": "abc", "details": "escaped json"}'

# Get all bctypes
curlget $EX_PERF_REPEAT $deviceauth bctypes

# Get bctypes/d1
curlget $EX_PERF_REPEAT $deviceauth bctypes/$bctypeid

# Blockchains =================================================

# Put (create) blockchains bc*
curlcreate "PUT" $EX_NUM_BLOCKCHAINS $userauth "bctypes/$bctypeid/blockchains/$blockchainbase" '{"description": "abc", "details": "escaped json"}'

# Put (update) bctype bt1 blockchain bc1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "bctypes/$bctypeid/blockchains/$blockchainid" '{"description": "abc", "details": "escaped json"}'

# Get all bctype bt1 blockchains
curlget $EX_PERF_REPEAT $deviceauth "bctypes/$bctypeid/blockchains"

# Get bctype bt1 blockchain bc1
curlget $EX_PERF_REPEAT $deviceauth "bctypes/$bctypeid/blockchains/$blockchainid"

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

# Delete devices d1 msgs
deletemsgs $deviceauth "devices/$deviceid/msgs"

# Delete agbot a1 msgs
deletemsgs $agbotauth "agbots/$agbotid/msgs"

# Delete devices d1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $deviceauth "devices/$deviceid/agreements/$agreementbase"

# Delete agbot a1 agreements agr*
curldelete $EX_NUM_AGREEMENTS $agbotauth "agbots/$agbotid/agreements/$agreementbase"

# Delete devices d*
curldelete $EX_NUM_DEVICES $userauth "devices/$devicebase"

# Delete agbot a*
curldelete $EX_NUM_AGBOTS $userauth "agbots/$agbotbase"

# Delete users u*
curldelete $EX_NUM_USERS $rootauth "users/$userbase"

bigtotal=$(($(date +%s)-bigstart))
echo "Overall: total=${bigtotal}s, num=$bignum, each=$(divide $bigtotal $bignum)s"