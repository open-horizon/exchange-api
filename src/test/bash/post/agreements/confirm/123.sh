# Confirms agreement exists
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" -d '{"agreementId":"123"}' $EXCHANGE_URL_ROOT/v1/agreements/confirm | $parse
