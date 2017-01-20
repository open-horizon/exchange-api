# Adds agreement 123 of agbot a1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_DEVAUTH" -d '{
  "message": "how do you do?"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1/msgs | $parse
