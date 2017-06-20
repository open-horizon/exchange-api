# Adds msg to device
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_AGBOTAUTH" -d '{
  "message": "hello again",
  "ttl": 300
}' $EXCHANGE_URL_ROOT/v1/devices/1/msgs | $parse
