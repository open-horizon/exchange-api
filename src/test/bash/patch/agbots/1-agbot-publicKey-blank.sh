# Update device 1 as device
source `dirname $0`/../../functions.sh PATCH $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_AGBOTAUTH" -d '{
  "publicKey": ""
}' $EXCHANGE_URL_ROOT/v1/agbots/a1 | $parse
