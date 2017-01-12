# Adds user 2
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X POST -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "password": "'$EXCHANGE_PW'",
  "email": "2@gmail.com"
}' $EXCHANGE_URL_ROOT/v1/users/2 | $parse
