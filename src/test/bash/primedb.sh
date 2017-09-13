#!/bin/bash

# Prime an empty DB with a few resources useful for testing with

#if [[ $1 == "-h" || $1 == "--help" ]]; then
#	echo "Usage: $0 [<name base>]"
#	exit 0
#fi
#
if [[ -z $EXCHANGE_ROOTPW ]]; then
    echo "Error: env var EXCHANGE_ROOTPW must be set and it must match what is in the exchange's config.json file"
    exit 2
fi
namebase=""

# Test configuration. You can override these before invoking the script, if you want.
EXCHANGE_URL_ROOT="${EXCHANGE_URL_ROOT:-http://localhost:8080}"
EXCHANGE_ORG="${EXCHANGE_ORG:-myorg}"
EXCHANGE_USER="${EXCHANGE_USER:-me}"
EXCHANGE_PW="${EXCHANGE_PW:-mypw}"
EXCHANGE_EMAIL="${EXCHANGE_EMAIL:-me@email.com}"
EXCHANGE_DEVAUTH="${EXCHANGE_DEVAUTH:-'d1:abc123'}"
EXCHANGE_AGBOTAUTH="${EXCHANGE_AGBOTAUTH:-'a1:abcdef'}"

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EXCHANGE_ROOTPW"

orgid=$EXCHANGE_ORG

user="$EXCHANGE_USER"
pw=$EXCHANGE_PW
userauth="$EXCHANGE_ORG/$user:$pw"
email=$EXCHANGE_EMAIL

deviceid=$(echo "$EXCHANGE_DEVAUTH" | cut -d: -f 1)
devicetoken=$(echo "$EXCHANGE_DEVAUTH" | cut -d: -f 2)
deviceauth="$EXCHANGE_ORG/$deviceid:$devicetoken"

agbotid=$(echo "$EXCHANGE_AGBOTAUTH" | cut -d: -f 1)
agbottoken=$(echo "$EXCHANGE_AGBOTAUTH" | cut -d: -f 2)
agbotauth="$EXCHANGE_ORG/$agbotid:$agbottoken"

agreementbase="${namebase}agreement"
agreementid="${agreementbase}1"

microid="bluehorizon.network-microservices-network_1.0.0_amd64"

workid="bluehorizon.network-workloads-netspeed_1.0.0_amd64"

patid="standard-horizon-edge-node"

#bctypebase="${namebase}bt"
#bctypeid="${bctypebase}1"
#
#blockchainbase="${namebase}bc"
#blockchainid="${blockchainbase}1"

#curlBasicArgs="-s -w %{http_code} --output /dev/null $accept"
curlBasicArgs="-s -w %{http_code} $accept"
# curlBasicArgs="-s -f"
# set -x

# Check the http code returned by curl. Args: returned rc, good rc, second good rc (optional)
function checkrc {
    out="$1"
    httpcode=${out:$((${#out}-3))}    # the last 3 chars are the http code
    #echo "httpcode=$httpcode"
    if [[ $httpcode != $2 && ( -z $3 ||  $httpcode != $3 ) ]]; then
	    #httpcode="${1:0:3}"     # when an error occurs with curl the rest method output comes in stderr with the http code
		echo "===> curl failed with: $out"
		#exit $httpcode
		exit 2
	fi
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 != 0 ]]; then
		echo "===> commands failed with exit code $1, exiting."
		exit $1
	fi
}

# set -x

function curlfind {
    auth="$1"
    url=$2
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
    #echo curl $curlBasicArgs $auth --output /dev/null $EXCHANGE_URL_ROOT/v1/$url
    rc=$(curl $curlBasicArgs $auth --output /dev/null $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    #checkrc "$rc" 200 404  # <- do not do this inside curl find because it can not output an error msg (it gets gobbled up by the caller)
    echo "$rc"
}

function curlcreate {
    method=$1
    auth="$2"
    url=$3
    body=$4
	if [[ $auth != "" ]]; then
		auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
	fi
    echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url
    rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    checkrc "$rc" 201
}

function curlputpost {
    method=$1
    auth=$2
    url=$3
    body="$4"
	auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    if [[ $body == "" ]]; then
        echo curl -X $method $curlBasicArgs $auth $EXCHANGE_URL_ROOT/v1/$url
        rc=$(curl -X $method $curlBasicArgs $auth $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    else
        echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url
        rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    fi
    checkrc "$rc" 201
}

rc=$(curlfind "root/root:$EXCHANGE_ROOTPW" "orgs/$orgid")
checkrc "$rc" 200 404
if [[ $rc == 404 ]]; then
    curlcreate "POST" "root/root:$EXCHANGE_ROOTPW" "orgs/$orgid" '{"label": "An org", "description": "blah blah"}'
else
    echo "orgs/$orgid exists"
fi

rc=$(curlfind "root/root:$EXCHANGE_ROOTPW" "orgs/$orgid/users/$user")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
        curlcreate "PUT" "root/root:$EXCHANGE_ROOTPW" "orgs/$orgid/users/$user" '{"password": "'$pw'", "email": "'$email'"}'
else
    echo "orgs/$orgid/users/$user exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/devices/$deviceid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/devices/$deviceid" '{"token": "'$devicetoken'", "name": "rpi1",
  "registeredMicroservices": [
    {
      "url": "https://bluehorizon.network/microservices/network",
      "numAgreements": 1,
      "policy": "{json policy for rpi1 netspeed}",
      "properties": [
        { "name": "arch", "value": "arm", "propType": "string", "op": "in" },
        { "name": "version", "value": "1.0.0", "propType": "version", "op": "in" }
      ]
    }
  ],
  "msgEndPoint": "", "softwareVersions": {}, "publicKey": "ABC" }'
else
    echo "orgs/$orgid/devices/$deviceid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "msgEndPoint": "whisper-id", "publicKey": "ABC"}'
else
    echo "orgs/$orgid/agbots/$agbotid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid/agreements/$agreementid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $agbotauth "orgs/$orgid/agbots/$agbotid/agreements/$agreementid" '{"workload": "sdr-arm.json", "state": "negotiating"}'
else
    echo "orgs/$orgid/agbots/$agbotid/agreements/$agreementid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/microservices/$microid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/microservices" '{"label": "Network x86_64", "description": "blah blah", "public": true, "specRef": "https://bluehorizon.network/microservices/network",
  "version": "1.0.0", "arch": "amd64", "sharable": "singleton", "downloadUrl": "",
  "matchHardware": {},
  "userInput": [],
  "workloads": [] }'
else
    echo "orgs/$orgid/microservices/$microid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/workloads/$workid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/workloads" '{"label": "Netspeed x86_64", "description": "blah blah", "public": true, "workloadUrl": "https://bluehorizon.network/workloads/netspeed",
  "version": "1.0.0", "arch": "amd64", "downloadUrl": "",
  "apiSpec": [{ "specRef": "https://bluehorizon.network/microservices/network", "version": "1.0.0", "arch": "amd64" }],
  "userInput": [],
  "workloads": [] }'
else
    echo "orgs/$orgid/workloads/$workid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/patterns/$patid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/patterns/$patid" '{"label": "My Pattern", "description": "blah blah", "public": true,
  "microservices": [
    {
      "specRef": "https://bluehorizon.network/microservices/gps",
      "version": "1.0.0",
      "arch": "amd64"
    }
  ],
  "workloads": [
    {
   	  "workloadUrl":"https://bluehorizon.network/workloads/weather",
   	  "version":"1.0.1",
   	  "arch":"amd64",
   	  "priority": {"priority_value":50, "retries":1, "retry_durations":3600, "verified_durations":52},
   	  "upgradePolicy": {"lifecycle":"immediate", "time":"01:00AM"}
    }
  ],
  "dataVerification": {
    "enabled": true,
    "URL": "",
    "user": "",
    "password": "",
    "interval": 240,
    "metering" : {"tokens":1, "per_time_unit":"min", "notification_interval":30}
  },
  "agreementProtocols": [{ "name": "Basic" }],
  "properties": [{ "name": "iame2edev", "value": true }],
  "counterPartyProperties": { "and": [{"name": "neverfound", "value": true}] },
  "maxAgreements": 0 }'
else
    echo "orgs/$orgid/patterns/$patid exists"
fi
exit

curlcreate "PUT" $userauth "orgs/$orgid/devices/$deviceid" '{"token": "'$devicetoken'", "name": "pi", "registeredMicroservices": [{"url": "https://bluehorizon.network/documentation/sdr-device-api", "numAgreements": 1, "policy": "{blob}", "properties": [{"name": "arch", "value": "arm", "propType": "string", "op": "in"},{"name": "version", "value": "1.0", "propType": "version", "op": "in"}]}], "msgEndPoint": "whisper-id", "softwareVersions": {"horizon": "3.2.1"}, "publicKey": "ABC"}'
curlcreate "PUT" $deviceauth "orgs/$orgid/devices/$deviceid/agreements/$agreementid" '{"microservice": "sdr", "state": "negotiating"}'

curlputpost "POST" $agbotauth "orgs/$orgid/devices/$deviceid/msgs" '{"message": "hey there", "ttl": 300}'
curlputpost "POST" $deviceauth "orgs/$orgid/agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'

#curlcreate "PUT" $userauth "bctypes/$bctypebase" '{"description": "abc", "details": "escaped json"}'
#curlcreate "PUT" $userauth "bctypes/$bctypeid/blockchains/$blockchainbase" '{"description": "abc", "details": "escaped json"}'
