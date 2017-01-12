# Gets device 1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/devices/1'?id=1&token=abc123' | $parse
