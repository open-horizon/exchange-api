# Tries to get a user with bad user creds
source `dirname $0`/../../functions.sh GET $*

curl $copts -X GET -H 'Accept: application/json' -H "Authorization:Basic baduser:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER | $parse