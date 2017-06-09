# Confirms the user/pw of a user
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm'?password='$EXCHANGE_PW | $parse
