# Adds a service
source `dirname $0`/../../functions.sh '' '' $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "Location for amd64",
  "description": "blah blah",
  "public": true,
  "url": "https://bluehorizon.network/services/location",
  "version": "4.5.6",
  "arch": "amd64",
  "sharable": "single",
  "matchHardware": {},
  "requiredServices": [
    {
      "url": "https://bluehorizon.network/services/foo",
      "org": "IBM",
      "version": "[1.0.0,INFINITY)",
      "arch": "amd64"
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
  "pkg": {
    "storeType": "dockerRegistry"
  }
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/services | $parse
