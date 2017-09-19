# Sends heartbeat from node 1
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/nodes/n1/heartbeat | $parse
