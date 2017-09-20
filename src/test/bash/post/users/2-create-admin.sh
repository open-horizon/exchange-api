# Adds a user
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "'$EXCHANGE_PW'",
  "admin": false,
  "email": "'$EXCHANGE_EMAIL'"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users/${EXCHANGE_USER}2 | $parse
