# Tries to get a hash of a pw as user (not allowed)
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "'$EXCHANGE_ROOTPW'"
}' $EXCHANGE_URL_ROOT/v1/admin/hashpw | $parse
