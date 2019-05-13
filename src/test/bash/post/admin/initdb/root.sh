# Reloads config.json
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic root/root:$EXCHANGE_ROOTPW" $HZN_EXCHANGE_URL/admin/initdb | $parse
