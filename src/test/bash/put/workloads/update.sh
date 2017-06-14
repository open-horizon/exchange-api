# Adds a workload
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "Location for x86_64",
  "description": "blah blah",
  "workloadUrl": "https://bluehorizon.network/documentation/workload/location",
  "version": "1.0.0",
  "arch": "amd64",
  "downloadUrl": "this is not used yet",
  "apiSpec": [
    {
      "specRef": "https://bluehorizon.network/documentation/microservice/gps",
      "version": "1.0.0",
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
  "workloads": [
    {
      "deployment": "{\"services\":{\"location\":{\"image\":\"summit.hovitos.engineering/x86/location:2.0.6\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}' $EXCHANGE_URL_ROOT/v1/workloads/bluehorizon.network-documentation-workload-location_1.0.0_amd64 | $parse
