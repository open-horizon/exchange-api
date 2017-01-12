# Updates the root email address
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" -d '{
  "password": "'$EXCHANGE_PW'",
  "email": "2-updated@gmail.com"
}' $EXCHANGE_URL_ROOT/v1/users/2 | $parse
