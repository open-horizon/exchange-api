# Sends heartbeat from device 1
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" $EXCHANGE_URL_ROOT/v1/devices/1/heartbeat | $parse
