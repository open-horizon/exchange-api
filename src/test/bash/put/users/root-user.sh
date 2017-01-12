# Updates the root email address
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X PUT -H 'Content-Type: application/json' -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_USER:$EXCHANGE_PW" -d '{
  "password": "'$EXCHANGE_ROOTPW'",
  "email": "thisshouldnotwork@root.com"
}' $EXCHANGE_URL_ROOT/v1/users/root | $parse
