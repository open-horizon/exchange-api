# Confirms the new pw of user (after changing it with a reset token)
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:newpw" $EXCHANGE_URL_ROOT/v1/users/$EXCHANGE_USER/confirm | $parse
