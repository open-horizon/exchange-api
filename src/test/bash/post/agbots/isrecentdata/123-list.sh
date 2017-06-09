# Updates the data received timestamp for agreement 123 of abbot a1
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" -d '{"secondsStale":300, "agreementIds":["123"]}' $EXCHANGE_URL_ROOT/v1/agbots/a1/isrecentdata | $parse
