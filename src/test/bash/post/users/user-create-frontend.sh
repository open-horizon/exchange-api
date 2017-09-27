# Updates the root user
source `dirname $0`/../../functions.sh PUT $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "type:person" -H "id:feuser" -H "orgid:$EXCHANGE_ORG" -H "issuer:IBM_ID" -d '{
  "password": "notused",
  "admin": true,
  "email": "'$EXCHANGE_EMAIL'"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users/feuser | $parse
