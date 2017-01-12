# Tries to get an agreement of agbot a1 that does not exit
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X GET -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/agbots/a1/agreements/9999 | $parse