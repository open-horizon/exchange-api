# Reloads config.json
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_RESET_TOKEN" $EXCHANGE_URL_ROOT/v1/admin/dropdb | $parse
