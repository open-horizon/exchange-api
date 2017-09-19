# Adds agreement 123 of agbot a1
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_NODEAUTH" -d '{
  "message": "how do you do?",
  "ttl": 300
}' $EXCHANGE_URL_ROOT/v1/agbots/a1/msgs | $parse
