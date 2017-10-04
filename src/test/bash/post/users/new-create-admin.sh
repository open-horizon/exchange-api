# Adds a user
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "'$EXCHANGE_NEWPW'",
  "admin": false,
  "email": "'$EXCHANGE_NEWEMAIL'"
}' $EXCHANGE_URL_ROOT/v1/orgs/$EXCHANGE_ORG/users/$EXCHANGE_NEWUSER | $parse
