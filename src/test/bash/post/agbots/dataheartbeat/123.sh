# Updates the data received timestamp for agreement 123 of abbot a1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" $EXCHANGE_URL_ROOT/v1/agbots/a1/dataheartbeat/123 | $parse
