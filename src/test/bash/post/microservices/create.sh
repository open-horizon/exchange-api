# Adds a microservice
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "GPS x86_64",
  "description": "blah blah",
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "downloadUrl": "",
  "matchHardware": {
    "usbDeviceIds": "1546:01a7",
    "devFiles": "/dev/ttyUSB*"
  },
  "userInput": "foo",
  "workloads": "bar"
}' $EXCHANGE_URL_ROOT/v1/microservices | $parse
