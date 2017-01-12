# Changes the user's pw using the reset token. Run like: EXCHANGE_RESET_TOKEN="<token>" user.sh
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_RESET_TOKEN" -d '{
  "newPassword": "mypw"
}' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/changepw | $parse
