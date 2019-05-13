# Reloads config.json
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_RESET_TOKEN" $HZN_EXCHANGE_URL/admin/dropdb | $parse
