# Adds a service
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "Location for amd64",
  "description": "updated sharable attribute",
  "public": true,
  "url": "https://bluehorizon.network/services/gps",
  "version": "1.2.3",
  "arch": "amd64",
  "sharable": "foobar",
  "deployment": "{\"services\":{\"gps\":{\"image\":\"summit.hovitos.engineering/x86/gps:1.2.3\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
  "deploymentSignature": "EURzSkDyk66qE6esYUDkLWLzM="
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/services/bluehorizon.network-services-gps_1.2.3_amd64 | $parse
