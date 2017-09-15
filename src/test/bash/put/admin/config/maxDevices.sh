# Sets 1 config value
source `dirname $0`/../../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "varPath": "api.limits.maxnodes",
  "value": "1"
}' $EXCHANGE_URL_ROOT/v1/admin/config | $parse
