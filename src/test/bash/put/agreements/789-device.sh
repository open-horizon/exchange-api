# Adds agreement 789 of device 1 as the device
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" -d '{
  "microservice": "https://bluehorizon.network/documentation/sdr-device-api",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/devices/1/agreements/789 | $parse
