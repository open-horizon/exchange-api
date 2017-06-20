# Adds blockchain to exchange
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "descriptionx": "bc1 desc bad input",
  "details": "json escaped string4"
}' $EXCHANGE_URL_ROOT/v1/bctypes/bt1/blockchains/bc1 | $parse
