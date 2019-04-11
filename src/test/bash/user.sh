# Run rest api methods as user. Most useful for methods that do not need an input body, like GET and DELETE.
source `dirname $0`/functions.sh $1 $2 ${@:3}

#echo curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" $HZN_EXCHANGE_URL/${org}$resource
curl $copts -X $method -H "Authorization:Basic $HZN_ORG_ID/$HZN_EXCHANGE_USER_AUTH" $HZN_EXCHANGE_URL/${org}$resource | $parse
