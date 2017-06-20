# Adds bc type to exchange
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "description": "abc"
}' $EXCHANGE_URL_ROOT/v1/bctypes/bt1 | $parse
