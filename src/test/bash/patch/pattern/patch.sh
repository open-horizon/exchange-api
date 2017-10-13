# Updates a microservice
source `dirname $0`/../../functions.sh PATCH $*

curl $copts -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "description": "this is now patched"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/patterns/p1 | $parse
