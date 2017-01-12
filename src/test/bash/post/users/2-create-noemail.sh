# Tries to add a user w/o an email address (invalid)
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -d '{
  "password": "'$EXCHANGE_PW'",
  "email": ""
}' $EXCHANGE_URL_ROOT/v1/users/2 | $parse
