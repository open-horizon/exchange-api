# Changes the user's pw using the reset token. Run like: EXCHANGE_RESET_TOKEN="<token>" user.sh
source `dirname $0`/../../../functions.sh POST $*

curl $copts -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_RESET_TOKEN" -d '{
  "newPassword": "'$EXCHANGE_PW'"
}' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/changepw | $parse
