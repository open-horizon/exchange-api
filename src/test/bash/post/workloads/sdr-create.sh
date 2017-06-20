# Adds a workload
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "SDR for arm",
  "description": "blah blah",
  "workloadUrl": "https://bluehorizon.network/documentation/workload/apollo",
  "version": "1.0.0",
  "arch": "arm",
  "downloadUrl": "",
  "apiSpec": [
    {
      "specRef": "https://bluehorizon.network/documentation/microservice/rtlsdr",
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
      "deployment": "{\"services\":{\"apollo\":{\"image\":\"summit.hovitos.engineering/armhf/apollo:2.1\",\"environment\":[\"USE_NEW_STAGING_URL=false\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}' $EXCHANGE_URL_ROOT/v1/workloads | $parse
