# Adds a microservice
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "label": "SDR arm",
  "description": "blah blah",
  "specRef": "https://bluehorizon.network/documentation/microservice/rtlsdr",
  "version": "1.0.0",
  "arch": "arm",
  "sharable": "none",
  "downloadUrl": "",
  "matchHardware": {},
  "userInput": "foo",
  "workloads": "bar"
}' $EXCHANGE_URL_ROOT/v1/microservices | $parse
