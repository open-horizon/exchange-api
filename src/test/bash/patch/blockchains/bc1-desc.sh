# Update device 1 as device
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PATCH -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "description": "A more thorough blockchain description..."
}' $EXCHANGE_URL_ROOT/v1/bctypes/bt1/blockchains/bc1 | $parse
