# Adds a microservice
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "SDR for arm",
  "description": "blah blah",
  "public": true,
  "specRef": "https://bluehorizon.network/documentation/microservice/rtlsdr",
  "version": "1.0.0",
  "arch": "arm",
  "sharable": "none",
  "downloadUrl": "",
  "matchHardware": {},
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
      "deployment": "{\"services\":{\"rtlsdr\":{\"image\":\"summit.hovitos.engineering/armhf/rtlsdr:volcano\",\"privileged\":true,\"nodes\":[\"/dev/bus/usb/001/001:/dev/bus/usb/001/001\"]}}}",
      "deployment_signature": "EURzSkDyk66qE6esYUDkLWLzM=",
      "torrent": "{\"url\":\"https://images.bluehorizon.network/28f57c.torrent\",\"images\":[{\"file\":\"d98bf.tar.gz\",\"signature\":\"kckH14DUj3bX=\"}]}"
    }
  ]
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/microservices | $parse
