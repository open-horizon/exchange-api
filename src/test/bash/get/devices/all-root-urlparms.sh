# Gets all devices as root
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X GET -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/devices'?id=root&token='$EXCHANGE_ROOTPW | $parse