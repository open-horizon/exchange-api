#!/bin/bash

# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! WARNING !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
# This script will remove all of your exchange data if you give it the --yesdoit flag

if [[ -z $EXCHANGE_ROOTPW ]]; then
    echo "Error: env var EXCHANGE_ROOTPW must be set and it must match what is in the exchange's config.json file"
    exit 2
fi

# You can override this as an env var before running this script
EXCHANGE_URL_ROOT="${EXCHANGE_URL_ROOT:-http://localhost:8080}"

if [[ $1 != "--yesdoit"  ]]; then
	echo "Specify the flag --yesdoit to delete all of the data from $EXCHANGE_URL_ROOT"
	exit 0
fi

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"

rootauth="root/root:$EXCHANGE_ROOTPW"
#rootauth="root:$EXCHANGE_ROOTPW"

#curlBasicArgs="-s -w %{http_code} --output /dev/null $accept"
 curlBasicArgs="-s -w %{http_code} $accept"
# curlBasicArgs="-s -f"

# Check the http code returned by curl. Args: returned rc, good rc, second good rc (optional)
function checkrc {
    out="$1"
    httpcode=${out:$((${#out}-3))}    # the last 3 chars are the http code
    if [[ $httpcode != $2 && ( -z $3 ||  $httpcode != $3 ) ]]; then
	    #httpcode="${1:0:3}"     # when an error occurs with curl the rest method output comes in stderr with the http code
		echo "===> curl failed with: $output"
		#exit $httpcode
		exit 2
	fi
}

# Check the exit code of the cmd that was run
function checkexitcode {
	if [[ $1 != 0 ]]; then
		echo "===> commands failed with exit code $1"
		exit $1
	fi
}

function striphttpcode {
    s="$1"
    echo "${s:0:$((${#s}-3))}"
}
# set -x

echo curl -X GET $curlBasicArgs -H "Authorization:Basic $rootauth" $EXCHANGE_URL_ROOT/v1/admin/dropdb/token
output=$(curl -X GET $curlBasicArgs -H "Authorization:Basic $rootauth" $EXCHANGE_URL_ROOT/v1/admin/dropdb/token 2>&1)
checkrc "$output" 200

output=$(striphttpcode "$output")
token=$(echo "$output" | jq -r .token)
checkexitcode $?
#echo "token: $token"

echo curl -X POST $curlBasicArgs -H "Authorization:Basic root/root:$token" $EXCHANGE_URL_ROOT/v1/admin/dropdb
output=$(curl -X POST $curlBasicArgs -H "Authorization:Basic root/root:$token" $EXCHANGE_URL_ROOT/v1/admin/dropdb 2>&1)
checkrc "$output" 201
echo "$output"
