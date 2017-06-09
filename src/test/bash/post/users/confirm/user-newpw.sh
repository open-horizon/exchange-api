# Confirms the new pw of user (after changing it with a reset token)
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:newpw" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm | $parse
