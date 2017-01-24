# Update device 1 as device
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_DEVAUTH" -d '{
  "publicKey": "newABCDEF"
}' $EXCHANGE_URL_ROOT/v1/devices/1 | $parse
