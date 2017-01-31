# Adds agreement 123 of agbot a1
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic a1:abcdef" -d '{
  "workload": "sdr-arm.json",
  "state": "negotiating"
}' $EXCHANGE_URL_ROOT/v1/agbots/a1/agreements/456 | $parse
