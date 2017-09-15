# Adds agreement 456 of node 2
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "microservice": "https://bluehorizon.network/documentation/netspeed-node-api",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/nodes/1/agreements/456 | $parse
