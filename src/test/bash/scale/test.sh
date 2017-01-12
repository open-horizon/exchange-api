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
EX_PERF_REPEAT="${EX_PERF_REPEAT:-30}"
EX_URL_ROOT="${EXCHANGE_URL_ROOT:-http://localhost:8080}"
EX_ROOT_PW="${EX_ROOT_PW:-Horizon-Rul3s}"	# this has to match what is in the exchange config.json

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

curlBasicArgs="-s -w %{http_code} --output /dev/null $accept"
# curlBasicArgs="-s -w %{http_code} $accept"
# curlBasicArgs="-s -f"
# set -x

function checkrc {
	if [[ $1 != $2 ]]; then
		echo "=======================================> curl failed with rc $1, exiting."
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
	echo "Running GET ($2) $3 $1 times:"
	start=`date +%s`
	for (( i=1 ; i<=$1 ; i++ )) ; do
		# rc=$(curl -X GET $curlBasicArgs -H "$accept" -H "Authorization:Basic $2" $EX_URL_ROOT/v1/$3)
		rc=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $2" $EX_URL_ROOT/v1/$3)
		checkrc $rc 200
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$1, each=$(divide $total $1)s"
}

# Args: numtimes, auth, url-base, body
function curlcreate {
	echo "Running PUT/create ($2) $3 $1 times:"
	start=`date +%s`
	if [[ $2 == "" ]]; then
		auth=""
	else
		auth="-H Authorization:Basic$2"
	fi
	for (( i=1 ; i<=$1 ; i++ )) ; do
		rc=$(curl -X PUT $curlBasicArgs $content $auth -d "$4" $EX_URL_ROOT/v1/$3$i)
		checkrc $rc 201
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$1, each=$(divide $total $1)s"
}

# Args: PUT/POST, numtimes, auth, url, body
function curlputpost {
	echo "Running $1 ($3) $4 $2 times:"
	start=`date +%s`
	auth="-H Authorization:Basic$3"
	for (( i=1 ; i<=$2 ; i++ )) ; do
		if [[ $5 == "" ]]; then
			rc=$(curl -X $1 $curlBasicArgs $auth $EX_URL_ROOT/v1/$4)
		else
			rc=$(curl -X $1 $curlBasicArgs $content $auth -d "$5" $EX_URL_ROOT/v1/$4)
		fi
		checkrc $rc 201
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$2, each=$(divide $total $2)s"
}

# Args: numtimes, auth, url-base
function curldelete {
	echo "Running DELETE ($2) $3 $1 times:"
	start=`date +%s`
	auth="-H Authorization:Basic$2"
	for (( i=1 ; i<=$1 ; i++ )) ; do
		rc=$(curl -X DELETE $curlBasicArgs $auth $EX_URL_ROOT/v1/$3$i)
		checkrc $rc 204
		echo -n .
		bignum=$(($bignum+1))
	done
	total=$(($(date +%s)-start))
	echo " total=${total}s, num=$1, each=$(divide $total $1)s"
}

bignum=0
bigstart=`date +%s`

# Put (create) users u*
curlcreate $EX_NUM_USERS "" "users/$userbase" '{"password": "pw", "email": "foo@gmail.com"}'

# Put (update) users/u1
curlputpost "PUT" $EX_PERF_REPEAT $userauth "users/$user" '{"password": "'$pw'", "email": "'$user'@gmail.com"}'

# Get all users
curlget $EX_PERF_REPEAT $rootauth "users"

# Get users/u1
curlget $EX_PERF_REPEAT $userauth "users/$user"

# Post users/u1/confirm
curlputpost "POST" $EX_PERF_REPEAT $userauth users/$user/confirm

# Put (create) devices d*
curlcreate $EX_NUM_DEVICES $userauth "devices/$devicebase" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}}'

# Put (update) devices/d1
curlputpost "PUT" $EX_PERF_REPEAT $deviceauth "devices/$deviceid" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}}'

# Get all devices
curlget $EX_PERF_REPEAT $userauth devices

# Get devices/d1
curlget $EX_PERF_REPEAT $deviceauth devices/$deviceid

# Post devices/d1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $deviceauth devices/$deviceid/heartbeat

# Put (create) agbots a*
curlcreate $EX_NUM_AGBOTS $userauth "agbots/$agbotbase" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id"}'

# Put (update) agbots/a1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id"}'

# Get all agbots
curlget $EX_PERF_REPEAT $userauth agbots

# Get agbots/a1
curlget $EX_PERF_REPEAT $agbotauth agbots/$agbotid

# Post agbots/a1/heartbeat
curlputpost "POST" $EX_PERF_REPEAT $agbotauth agbots/$agbotid/heartbeat

# Post search/devices
curlputpost "POST" $EX_PERF_REPEAT $agbotauth search/devices '{"desiredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "properties": [{"name": "arch", "value": "arm", "propType": "wildcard", "op": "in"}, {"name": "version", "value": "*", "propType": "version", "op": "in"}]}], "secondsStale": 0, "propertiesToReturn": ["string"], "startIndex": 0, "numEntries": 0}'

# Put (create) device d1 agreements agr*
curlcreate $EX_NUM_AGREEMENTS $deviceauth "devices/$deviceid/agreements/$agreementbase" '{"microservice": "sdr", "state": "negotiating"}'

# Put (update) device d1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements/$agreementid" '{"microservice": "sdr", "state": "negotiating"}'

# Get all device d1 agreements
curlget $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements"

# Get device d1 agreement agr1
curlget $EX_PERF_REPEAT $deviceauth "devices/$deviceid/agreements/$agreementid"

# Put (create) agbot a1 agreements agr*
curlcreate $EX_NUM_AGREEMENTS $agbotauth "agbots/$agbotid/agreements/$agreementbase" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Put (update) agbot a1 agreement agr1
curlputpost "PUT" $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid" '{"workload": "sdr-arm.json", "state": "negotiating"}'

# Get all agbot a1 agreements
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements"

# Get agbot a1 agreement agr1
curlget $EX_PERF_REPEAT $agbotauth "agbots/$agbotid/agreements/$agreementid"

# Post admin/hashpw
curlputpost "POST" $EX_PERF_REPEAT $rootauth admin/hashpw '{"password": "somepw"}'

# Post admin/reload
curlputpost "POST" $EX_PERF_REPEAT $rootauth admin/reload

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