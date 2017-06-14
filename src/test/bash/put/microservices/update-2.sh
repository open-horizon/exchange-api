# Updates a microservice as the wrong user
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 2:$EXCHANGE_PW" -d '{
  "label": "GPS x86_64",
  "description": "blah blah",
  "specRef": "https://bluehorizon.network/documentation/microservice/gps",
  "version": "1.0.0",
  "arch": "amd64",
  "sharable": "singleton",
  "downloadUrl": "this should not work",
  "matchHardware": {
    "usbDeviceIds": "1546:01a7",
    "devFiles": "/dev/ttyUSB*"
  },
  "userInput": "foo",
  "workloads": "bar"
}' $EXCHANGE_URL_ROOT/v1/microservices/bluehorizon.network-documentation-microservice-gps_1.0.0_amd64 | $parse
