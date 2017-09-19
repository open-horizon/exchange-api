# Update node 1 as node
source `dirname $0`/../../functions.sh PATCH $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "publicKey": "newAGBOTABCDEF"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1 | $parse
