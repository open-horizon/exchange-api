# Adds a service
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "GPS for amd64",
  "description": "blah blah",
  "public": true,
  "url": "https://bluehorizon.network/services/gps",
  "version": "1.2.3",
  "arch": "amd64",
  "sharable": "single",
  "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:1.2.3\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM="
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/services | $parse
