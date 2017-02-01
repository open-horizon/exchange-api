# Adds agbot a2 as root
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "token": "abcdef",
  "name": "agbot2-asroot",
  "msgEndPoint": "whisper-id",
  "publicKey": "AGBOTDEF"
}' $EXCHANGE_URL_ROOT/v1/agbots/a2 | $parse
