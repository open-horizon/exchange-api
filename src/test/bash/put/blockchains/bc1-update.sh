# Adds agbot a1 to exchange
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "description": "bc1 desc updated",
  "bootNodes": [ "url1", "url2" ],
  "genesis": [ "gen1", "gen2" ],
  "networkId": [ "net1", "net2" ]
}' $EXCHANGE_URL_ROOT/v1/bctypes/bt1/blockchains/bc1 | $parse
