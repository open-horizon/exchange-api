# Updates the data received timestamp for agreement 123 of abbot a1
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" $EXCHANGE_URL_ROOT/v1/agbots/a1/dataheartbeat/123 | $parse
