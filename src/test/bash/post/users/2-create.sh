# Adds user 2
source `dirname $0`/../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "password": "'$EXCHANGE_PW'",
  "email": "2@gmail.com"
}' $EXCHANGE_URL_ROOT/v1/users/2 | $parse
