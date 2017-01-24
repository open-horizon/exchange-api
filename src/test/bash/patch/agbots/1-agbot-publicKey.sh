# Update device 1 as device
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_AGBOTAUTH" -d '{
  "publicKey": "newAGBOTABCDEF"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1 | $parse
