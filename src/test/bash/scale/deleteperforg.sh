#!/bin/bash

# Completely cleans up after a node/agbot performance test run by deleting the org used for that.
# This can not be done inside the scripts running the perf test, because if multiple instances are run on multiple machines they can't know when
# they are all done with the org.

#if [[ -z $1 ]]; then
#	echo "Usage: $0 <perf-org>"
#	exit 1
#fi
#perfOrg=$1

# These env vars are required
if [[ -z "$EXCHANGE_ROOTPW" || -z "$HZN_EXCHANGE_URL" ]]; then
    echo "Error: environment variables EXCHANGE_ROOTPW and HZN_EXCHANGE_URL must be set."
    exit 1
fi

EX_PERF_ORG="${EX_PERF_ORG:-performancenodeagbot}"
url="orgs/$EX_PERF_ORG"
rootauth="root/root:$EXCHANGE_ROOTPW"
auth="$rootauth"
#echo "Running DELETE $url ..."
auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it


if [[ -n "$EX_PERF_CERT_FILE" ]]; then
    certFile="--cacert $EX_PERF_CERT_FILE"
else
    certFile="-k"
fi

curlBasicArgs="-sS -w %{http_code} --output /dev/null $certFile"

echo curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url
rc=$(curl -X DELETE $curlBasicArgs $auth $HZN_EXCHANGE_URL/$url)

if [[ "$rc" == 204 ]]; then
    exit   # everything is good
elif [[ "$rc" == 404 ]]; then
    echo "$url does not exist"
    exit   # still return a 0 exit code, because it is gone like they want it to be
else
    httpcode="${rc:0:3}"     # when an error occurs with curl the rest method output comes in stderr with the http code
    echo "Error: DELETE $url failed with: $rc, exiting."
    exit $httpcode
fi

