# Requests a reset token email using no creds (allowed)
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/reset | $parse
