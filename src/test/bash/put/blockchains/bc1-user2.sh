# Adds blockchain to exchange
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/${EXCHANGE_USER}2:$EXCHANGE_PW" -d '{
  "description": "bc1 desc2",
  "public": true,
  "details": "json escaped string"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/bctypes/bct1/blockchains/bc1 | $parse
