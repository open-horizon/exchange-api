# Gets a hash of the specified pw
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "varPath": "api.limits.maxAgbots",
  "value": "1"
}' $EXCHANGE_URL_ROOT/v1/admin/config | $parse
