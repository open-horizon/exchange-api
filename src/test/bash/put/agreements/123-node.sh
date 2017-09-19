# Adds agreement 123 of node 1 as the node
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" -d '{
  "microservices": ["https://bluehorizon.network/documentation/sdr-node-api"],
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/nodes/1/agreements/123 | $parse
