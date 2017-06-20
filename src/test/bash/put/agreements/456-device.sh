# Adds agreement 456 of device 2
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "microservice": "https://bluehorizon.network/documentation/netspeed-device-api",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/devices/1/agreements/456 | $parse
