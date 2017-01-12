# Gets device 1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/devices/an12345'?token=abcdefg' | $parse
