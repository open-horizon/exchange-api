# Gets a user
if [[ $1 == "-raw" ]]; then parse=cat; else parse="jq -r ."; fi
curl -# -w "%{http_code}" -X GET -H 'Accept: application/json' -H "Authorization:Basic root:$EXCHANGE_ROOTPW" $EXCHANGE_URL_ROOT/v1/users/2 | $parse