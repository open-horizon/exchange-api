# Adds agbot a1 to exchange
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "description": "bc1 owned by root",
  "details": "json escaped string3"
}' $EXCHANGE_URL_ROOT/v1/bctypes/bt1/blockchains/bc1 | $parse
