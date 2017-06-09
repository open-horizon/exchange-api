# Reloads config.json
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_RESET_TOKEN" $EXCHANGE_URL_ROOT/v1/admin/dropdb | $parse
