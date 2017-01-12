# Confirms the user/pw of a user
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm'?password='$EXCHANGE_PW | $parse
