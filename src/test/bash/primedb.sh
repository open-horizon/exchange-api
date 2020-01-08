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
user="${EXCHANGE_USER:-me}"
pw="${EXCHANGE_PW:-mypw}"
EXCHANGE_EMAIL="${EXCHANGE_EMAIL:-me@email.com}"
EXCHANGE_NODEAUTH="${EXCHANGE_NODEAUTH:-n1:abc123}"
EXCHANGE_AGBOTAUTH="${EXCHANGE_AGBOTAUTH:-a1:abcdef}"

: ${EXCHANGE_IAM_ORG:?} : ${EXCHANGE_IAM_ACCOUNT_ID:?} : ${EXCHANGE_IAM_KEY:?}
orgidcloud="$EXCHANGE_IAM_ORG"
orgcloudid="$EXCHANGE_IAM_ACCOUNT_ID"
cloudapikey="$EXCHANGE_IAM_KEY"

#export EXCHANGE_CLIENT_CERT=../../../keys/etc/exchangecert.pem
if [[ -n "$EXCHANGE_CLIENT_CERT" && -f "$EXCHANGE_CLIENT_CERT" ]]; then
    if [[ "$EXCHANGE_URL_ROOT" == "http://localhost:8080" ]]; then
        echo "Setting EXCHANGE_URL_ROOT=https://localhost:8443"
        EXCHANGE_URL_ROOT="https://localhost:8443"
    fi
    echo "Using certificate $EXCHANGE_CLIENT_CERT"
    cacert="--cacert $EXCHANGE_CLIENT_CERT"
fi

rootauth="root/root:$EXCHANGE_ROOTPW"

appjson="application/json"
accept="-H Accept:$appjson"
content="-H Content-Type:$appjson"
contenttext="-H Content-Type:text/plain"

orgid="IBM"
orgid2="org2"
orgicp="major-peacock-icp-cluster"
orgicp2="edge-rtp-321-icp-cluster"

userauth="$orgid/$user:$pw"
email=$EXCHANGE_EMAIL
userauthorg2="$orgid2/iamapikey:$cloudapikey"

nodeid="${EXCHANGE_NODEAUTH%%:*}"
nodetoken="${EXCHANGE_NODEAUTH#*:}"
nodeauth="$orgid/$nodeid:$nodetoken"
nodeauthorg2="$orgid2/$nodeid:$nodetoken"
nodeid2="n2"
nodeauth2="$orgid/$nodeid2:$nodetoken"

agbotid="${EXCHANGE_AGBOTAUTH%%:*}"
agbottoken="${EXCHANGE_AGBOTAUTH#*:}"
agbotauth="$orgid/$agbotid:$agbottoken"

agreementbase="${namebase}agreement"
agreementid1="${agreementbase}1"
agreementid2="${agreementbase}2"
agreementid3="${agreementbase}3"

#resname="res1"
#resversion="7.8.9"
#resid="${resname}_$resversion"

svcid="ibm.gps_1.2.3_amd64"
svcurl="ibm.gps"
svcarch="amd64"
svcversion="1.2.3"

svcKeyId="mykey3.pem"
svcKey='-----BEGIN CERTIFICATE-----
MIII+jCCBOKgAwIBAgIUEfeMrmSFxCUKATcNPcowfs/lU9owDQYJKoZIhvcNAQEL
BQAwJjEMMAoGA1UEChMDaWJtMRYwFAYDVQQDDA1icEB1cy5pYm0uY29tMB4XDTE4
MDEwMjAxNDkyMFoXDTIyMDEwMjEzNDgzMFowJjEMMAoGA1UEChMDaWJtMRYwFAYD
VQQDDA1icEB1cy5pYm0uY29tMIIEIjANBgkqhkiG9w0BAQEFAAOCBA8AMIIECgKC
-----END CERTIFICATE-----
'

svc2id="ibm.location_4.5.6_amd64"
svc2url="ibm.location"
svc2arch="amd64"
svc2version="4.5.6"

svcDockAuthId='1'
svcDockAuthRegistry='registry.ng.bluemix.net'
svcDockAuthUsername='iamapikey'
svcDockAuthToken='abcdefghijk'

patid="p1"
patid2="p2"

ptKeyId="mykey4.pem"
ptKey='-----BEGIN CERTIFICATE-----
MIII+jCCBOKgAwIBAgIUEfeMrmSFxCUKATcNPcowfs/lU9owDQYJKoZIhvcNAQEL
BQAwJjEMMAoGA1UEChMDaWJtMRYwFAYDVQQDDA1icEB1cy5pYm0uY29tMB4XDTE4
MDEwMjAxNDkyMFoXDTIyMDEwMjEzNDgzMFowJjEMMAoGA1UEChMDaWJtMRYwFAYD
VQQDDA1icEB1cy5pYm0uY29tMIIEIjANBgkqhkiG9w0BAQEFAAOCBA8AMIIECgKC
-----END CERTIFICATE-----
'

buspol=mybuspol
buspol2=mybuspol2

#curlBasicArgs="-s -w %{http_code} --output /dev/null $accept"
curlBasicArgs="-sS -w %{http_code} $accept $cacert"
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
		#auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
		auth="-u $auth"
	fi
    #echo curl $curlBasicArgs $auth --output /dev/null $EXCHANGE_URL_ROOT/v1/$url >&2
    rc=$(curl $curlBasicArgs $auth --output /dev/null $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    #checkrc "$rc" 200 404  # <- do not do this inside curl find because it can not output an error msg (it gets gobbled up by the caller)
    echo "$rc"
}

function curlcreate {
    method=$1
    auth="$2"
    url=$3
    body="$4"
    cont="$5"
	if [[ $auth != "" ]]; then
		#auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
		auth="-u $auth"
	fi
	if [[ $cont == "" ]]; then
		cont="$content"
	fi
    echo curl -X $method $curlBasicArgs $cont $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url
    rc=$(curl -X $method $curlBasicArgs $cont $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    checkrc "$rc" 201
}

function curlputpost {
    method=$1
    auth=$2
    url=$3
    body="$4"
	  #auth="-H Authorization:Basic$auth"    # no spaces so we do not need to quote it
    auth="-u $auth"
    if [[ $body == "" ]]; then
        echo curl -X $method $curlBasicArgs $auth $EXCHANGE_URL_ROOT/v1/$url
        rc=$(curl -X $method $curlBasicArgs $auth $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    else
        echo curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url
        rc=$(curl -X $method $curlBasicArgs $content $auth -d "$body" $EXCHANGE_URL_ROOT/v1/$url 2>&1)
    fi
    checkrc "$rc" 201
}

# Create the IBM org - dont need to do this anymore, the exchange automatically does it
#rc=$(curlfind "$rootauth" "orgs/$orgid")
#checkrc "$rc" 200 404
#if [[ $rc == 404 ]]; then
#    curlcreate "POST" "$rootauth" "orgs/$orgid" '{"orgType": "IBM", "label": "An org", "description": "blah blah"}'
#else
#    echo "orgs/$orgid exists"
#fi

rc=$(curlfind "$rootauth" "orgs/$orgidcloud")
checkrc "$rc" 200 404
if [[ $rc == 404 ]]; then
    curlcreate "POST" "$rootauth" "orgs/$orgidcloud" '{"label": "BPs org", "description": "blah blah", "tags": {"ibmcloud_id":"'$orgcloudid'"} }'
else
    echo "orgs/$orgidcloud exists"
fi

rc=$(curlfind "$rootauth" "orgs/$orgicp")
checkrc "$rc" 200 404
if [[ $rc == 404 ]]; then
    curlcreate "POST" "$rootauth" "orgs/$orgicp" '{"label": "'$orgicp'", "description": "blah blah" }'
else
    echo "orgs/$orgicp exists"
fi

rc=$(curlfind "$rootauth" "orgs/$orgicp2")
checkrc "$rc" 200 404
if [[ $rc == 404 ]]; then
    curlcreate "POST" "$rootauth" "orgs/$orgicp2" '{"label": "'$orgicp2'", "description": "blah blah" }'
else
    echo "orgs/$orgicp2 exists"
fi

rc=$(curlfind "$rootauth" "orgs/$orgid2")
checkrc "$rc" 200 404
if [[ $rc == 404 ]]; then
    curlcreate "POST" "$rootauth" "orgs/$orgid2" '{"label": "Another org under BPs ibm cloud acct", "description": "blah blah", "tags": {"ibmcloud_id":"'$orgcloudid'"} }'
else
    echo "orgs/$orgid2 exists"
fi

rc=$(curlfind "$rootauth" "orgs/$orgid/users/$user")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
        curlcreate "POST" "$rootauth" "orgs/$orgid/users/$user" '{"password": "'$pw'", "admin": true, "email": "'$email'"}'
else
    echo "orgs/$orgid/users/$user exists"
fi

# since this is an ibm cloud org, the user will get created automatically
#rc=$(curlfind "$rootauth" "orgs/$orgid2/users/$user")
#checkrc "$rc" 200 404
#if [[ $rc != 200 ]]; then
#        curlcreate "POST" "$rootauth" "orgs/$orgid2/users/$user" '{"password": "'$pw'", "admin": true, "email": "'$email'"}'
#else
#    echo "orgs/$orgid2/users/$user exists"
#fi

rc=$(curlfind $userauth "orgs/$orgid/services/$svcid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/services" '{"label": "GPS for amd64", "description": "blah blah", "public": true, "url": "'$svcurl'",
  "version": "'$svcversion'", "arch": "'$svcarch'", "sharable": "singleton",
  "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:1.2.3\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=" }'
else
    echo "orgs/$orgid/services/$svcid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/services/$svcid/policy")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/services/$svcid/policy" '{ "properties": [{"name":"purpose", "value":"location", "type":"string"}], "constraints":["a == b"] }'
else
    echo "orgs/$orgid/services/$svcid/policy exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/services/$svcid/keys/$svcKeyId")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/services/$svcid/keys/$svcKeyId" "$svcKey" "$contenttext"
else
    echo "orgs/$orgid/services/$svcid/keys/$svcKeyId exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/services/$svcid/dockauths/$svcDockAuthId")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/services/$svcid/dockauths" '{"registry": "'$svcDockAuthRegistry'", "username": "'$svcDockAuthUsername'", "token": "'$svcDockAuthToken'"}'
else
    echo "orgs/$orgid/services/$svcid/dockauths/$svcDockAuthId exists"
fi

#rc=$(curlfind $userauth "orgs/$orgid/resources/$resid")
#checkrc "$rc" 200 404
#if [[ $rc != 200 ]]; then
#    curlcreate "POST" $userauth "orgs/$orgid/resources" '{"name": "'$resname'", "description": "blah blah", "public": true, "documentation": "https://myres.com/myres",
#  "version": "'$resversion'",
#  "resourceStore": { "url": "https://..." } }'
#else
#    echo "orgs/$orgid/resources/$resid exists"
#fi

rc=$(curlfind $userauth "orgs/$orgid/services/$svc2id")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/services" '{"label": "Location for amd64", "public": true, "url": "'$svc2url'",
  "version": "'$svc2version'", "arch": "'$svc2arch'", "sharable": "singleton",
  "requiredServices": [
    {
      "url": "'$svcurl'",
      "org": "'$orgid'",
      "version": "[1.0.0,INFINITY)",
      "arch": "'$svcarch'"
    }
  ],
  "userInput": [
    {
      "name": "foo",
      "label": "The Foo Value",
      "type": "string",
      "defaultValue": "bar"
    }
  ],
  "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:4.5.6\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM=",
  "imageStore": {
    "storeType": "dockerRegistry"
  }
}'
else
    echo "orgs/$orgid/services/$svc2id exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/services/$svcid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauthorg2 "orgs/$orgid2/services" '{"label": "GPS for amd64", "description": "blah blah", "public": false, "url": "'$svcurl'",
  "version": "'$svcversion'", "arch": "'$svcarch'", "sharable": "singleton",
  "deployment": "",
  "deploymentSignature": "" }'
else
    echo "orgs/$orgid2/services/$svcid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/patterns/$patid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/patterns/$patid" '{"label": "My Pattern", "description": "blah blah", "public": true,
  "services": [
    {
      "serviceUrl": "'$svc2url'",
      "serviceOrgid": "'$orgid'",
      "serviceArch": "'$svc2arch'",
      "agreementLess": false,
      "serviceVersions": [
        {
          "version": "'$svc2version'",
          "deployment_overrides": "{\"services\":{\"location\":{\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
          "deployment_overrides_signature": "a",
          "priority": {
            "priority_value": 50,
            "retries": 1,
            "retry_durations": 3600,
            "verified_durations": 52
          },
          "upgradePolicy": {
            "lifecycle": "immediate",
            "time": "01:00AM"
          }
        }
      ]
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "'$orgid'",
      "serviceUrl": "'$svc2url'",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "1234ABC"
        }
      ]
    }
  ],
  "agreementProtocols": [{ "name": "Basic" }] }'
else
    echo "orgs/$orgid/patterns/$patid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/patterns/$patid/keys/$ptKeyId")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/patterns/$patid/keys/$ptKeyId" "$ptKey" "$contenttext"
else
    echo "orgs/$orgid/patterns/$patid/keys/$ptKeyId exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/patterns/$patid2")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauthorg2 "orgs/$orgid2/patterns/$patid2" '{"label": "My other Pattern",
  "services": [
    {
      "serviceUrl": "'$svcurl'",
      "serviceOrgid": "'$orgid'",
      "serviceArch": "'$svcarch'",
      "serviceVersions": [
        {
          "version": "'$svcversion'"
        }
      ]
    }
  ] }'
else
    echo "orgs/$orgid2/patterns/$patid2 exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/business/policies/$buspol")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauthorg2 "orgs/$orgid2/business/policies/$buspol" '{ "label": "my business policy",
  "service": { "name": "'$svcurl'", "org": "'$orgid'", "arch": "'$svcarch'", "serviceVersions": [{ "version": "'$svcversion'" }] },
  "userInput": [
    {
      "serviceOrgid": "'$orgid'",
      "serviceUrl": "'$svc2url'",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "foo",
          "value": "1234ABC"
        }
      ]
    }
  ],
  "properties": [{"name":"purpose", "value":"location", "type":"string"}],
  "constraints":["a == b"] }'
else
    echo "orgs/$orgid2/business/policies/$buspol exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/business/policies/$buspol2")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauthorg2 "orgs/$orgid2/business/policies/$buspol2" '{ "label": "my business policy", "service": { "name": "'$svcurl'", "org": "'$orgid'", "arch": "*", "serviceVersions": [{ "version": "'$svcversion'" }] }, "properties": [{"name":"purpose", "value":"location", "type":"string"}], "constraints":["a == b"] }'
else
    echo "orgs/$orgid2/business/policies/$buspol2 exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/nodes/$nodeid" '{"token": "'$nodetoken'", "name": "rpi1", "pattern": "'$orgid'/'$patid'",
  "registeredServices": [
    {
      "url": "'$orgid'/'$svcurl'",
      "configState": "active",
      "numAgreements": 1,
      "policy": "{json policy for n1 gps}",
      "properties": [
        { "name": "arch", "value": "arm", "propType": "string", "op": "in" },
        { "name": "version", "value": "1.0.0", "propType": "version", "op": "in" }
      ]
    },
    {
      "url": "'$orgid2'/'$svc2url'",
      "configState": "active",
      "numAgreements": 1,
      "policy": "{json policy for n2 location}",
      "properties": []
    }
  ],
  "userInput": [
    {
      "serviceOrgid": "'$orgid'",
      "serviceUrl": "'$svcurl'",
      "serviceArch": "",
      "serviceVersionRange": "[0.0.0,INFINITY)",
      "inputs": [
        {
          "name": "MSGHUB_API_KEY",
          "value": "1234ABC"
        },
        {
          "name": "SAMPLE_INTERVAL",
          "value": 5
        },
        {
          "name": "VERBOSE",
          "value": true
        }
      ]
    }
  ],
  "publicKey": "ABC" }'
else
    echo "orgs/$orgid/nodes/$nodeid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid2")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/nodes/$nodeid2" '{"token": "'$nodetoken'", "name": "rpi1", "pattern": "'$orgid2'/'$patid2'", "registeredServices": [{"url": "'$orgid'/'$svcurl'", "numAgreements": 1, "policy": "", "properties": []}], "publicKey": "ABC" }'
else
    echo "orgs/$orgid/nodes/$nodeid2 exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/nodes/$nodeid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauthorg2 "orgs/$orgid2/nodes/$nodeid" '{"token": "'$nodetoken'", "name": "rpi1", "pattern": "'$orgid'/'$patid'", "registeredServices": [], "publicKey": "ABC" }'
else
    echo "orgs/$orgid2/nodes/$nodeid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid/status")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $nodeauth "orgs/$orgid/nodes/$nodeid/status" '{ "connectivity": {"firmware.bluehorizon.network": true}, "services": [] }'
else
    echo "orgs/$orgid/nodes/$nodeid/status exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid/policy")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $nodeauth "orgs/$orgid/nodes/$nodeid/policy" '{ "properties": [{"name":"purpose", "value":"testing", "type":"string"}], "constraints":["a == b"] }'
else
    echo "orgs/$orgid/nodes/$nodeid/policy exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/nodes/$nodeid2")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauthorg2 "orgs/$orgid2/nodes/$nodeid2" '{"token": "'$nodetoken'", "name": "rpi1", "pattern": "", "publicKey": "ABC" }'
else
    echo "orgs/$orgid2/nodes/$nodeid2 exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauth "orgs/$orgid/agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'
else
    echo "orgs/$orgid/agbots/$agbotid exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/agbots/$agbotid")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $userauthorg2 "orgs/$orgid2/agbots/$agbotid" '{"token": "'$agbottoken'", "name": "agbot", "publicKey": "ABC"}'
else
    echo "orgs/$orgid2/agbots/$agbotid exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid/patterns/${orgid}_${patid}_${orgid}")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/agbots/$agbotid/patterns" '{ "patternOrgid": "'$orgid'", "pattern": "'$patid'" }'
else
    echo "orgs/$orgid/agbots/$agbotid/patterns/${orgid}_${patid}_${orgid} exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid/patterns/${orgid2}_*_${orgid}")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/agbots/$agbotid/patterns" '{ "patternOrgid": "'$orgid2'", "pattern": "*", "nodeOrgid": "'$orgid'" }'
else
    echo "orgs/$orgid/agbots/$agbotid/patterns/${orgid2}_*_${orgid} exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid/businesspols/${orgid}_*_${orgid}")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "POST" $userauth "orgs/$orgid/agbots/$agbotid/businesspols" '{ "businessPolOrgid": "'$orgid'", "businessPol": "*" }'
else
    echo "orgs/$orgid/agbots/$agbotid/businesspols/${orgid}_*_${orgid} exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid/agreements/$agreementid1")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $nodeauth "orgs/$orgid/nodes/$nodeid/agreements/$agreementid1" '{"services": [], "agreementService": {"orgid": "'$orgid'", "pattern": "'$orgid'/'$patid'", "url": "'$orgid'/'$svc2url'"}, "state": "negotiating"}'
else
    echo "orgs/$orgid/nodes/$nodeid/agreements/$agreementid1 exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/nodes/$nodeid2/agreements/$agreementid2")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $nodeauth2 "orgs/$orgid/nodes/$nodeid2/agreements/$agreementid2" '{"services": [], "agreementService": {"orgid": "'$orgid2'", "pattern": "'$orgid'/'$patid2'", "url": "'$orgid'/'$svcurl'"}, "state": "negotiating"}'
else
    echo "orgs/$orgid/nodes/$nodeid2/agreements/$agreementid2 exists"
fi

rc=$(curlfind $userauthorg2 "orgs/$orgid2/nodes/$nodeid/agreements/$agreementid3")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $nodeauthorg2 "orgs/$orgid2/nodes/$nodeid/agreements/$agreementid3" '{"services": [], "agreementService": {"orgid": "'$orgid'", "pattern": "'$orgid'/'$patid'", "url": "'$orgid'/'$svc2url'"}, "state": "negotiating"}'
else
    echo "orgs/$orgid2/nodes/$nodeid/agreements/$agreementid3 exists"
fi

rc=$(curlfind $userauth "orgs/$orgid/agbots/$agbotid/agreements/$agreementid1")
checkrc "$rc" 200 404
if [[ $rc != 200 ]]; then
    curlcreate "PUT" $agbotauth "orgs/$orgid/agbots/$agbotid/agreements/$agreementid1" '{"service": {"orgid": "'$orgid'", "pattern": "'$patid'", "url": "'$svcurl'"}, "state": "negotiating"}'
else
    echo "orgs/$orgid/agbots/$agbotid/agreements/$agreementid1 exists"
fi

# Do not have a good way to know what msg id they will have, but it is ok to create additional msgs
curlputpost "POST" $agbotauth "orgs/$orgid/nodes/$nodeid/msgs" '{"message": "hey there", "ttl": 300}'
curlputpost "POST" $nodeauth "orgs/$orgid/agbots/$agbotid/msgs" '{"message": "hey there", "ttl": 300}'

echo "All resources added successfully"
