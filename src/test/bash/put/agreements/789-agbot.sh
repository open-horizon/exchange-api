# Adds agreement 789 of agbot a1
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" -d '{
  "workload": "sdr-arm.json",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1/agreements/789 | $parse
