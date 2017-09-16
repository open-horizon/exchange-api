# Adds blockchain to exchange
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "description": "bc1 desc updated",
  "details": "json escaped string2"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/bctypes/bt1/blockchains/bc1 | $parse
