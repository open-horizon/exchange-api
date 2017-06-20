# Requests a reset token email using using root creds
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/reset | $parse
