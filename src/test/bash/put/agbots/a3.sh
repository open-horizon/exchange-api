# Adds agbot a2
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "token": "abcdef",
  "name": "agbot3",
  "msgEndPoint": "whisper-id",
  "publicKey": "AGBOTDEF"
}' $EXCHANGE_URL_ROOT/v1/agbots/a3 | $parse
