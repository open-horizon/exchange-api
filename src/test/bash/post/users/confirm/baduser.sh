# Tries to confirm a user's pw using bad creds
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:x$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm | $parse
