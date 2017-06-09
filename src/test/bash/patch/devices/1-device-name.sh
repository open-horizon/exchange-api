# Update device 1 as device
source `dirname $0`/../../functions.sh PATCH $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "name": "rpi1-partial-update"
}' $EXCHANGE_URL_ROOT/v1/devices/1 | $parse
