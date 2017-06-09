# Adds agreement 123 of agbot a1
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "workload": "sdr-arm.json",
  "state": "finalized"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1/agreements/123 | $parse
