# Tries to add a user w/o an email address (invalid)
source `dirname $0`/../../functions.sh POST $*

curl $copts -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "password": "'$EXCHANGE_PW'",
  "email": ""
}' $EXCHANGE_URL_ROOT/v1/users/2 | $parse
