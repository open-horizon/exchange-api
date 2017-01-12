# Deletes agreement 123 for device 1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X DELETE -H 'Accept: application/json' -H "Authorization:Basic 1:abc123" $EXCHANGE_URL_ROOT/v1/devices/1/agreements/123 | $parse