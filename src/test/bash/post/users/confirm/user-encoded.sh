# Confirms the user/pw of a user
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Accept: application/json' -H "Authorization:Basic $(echo -n $EXCHANGE_USER:$EXCHANGE_PW | base64)" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm | $parse
