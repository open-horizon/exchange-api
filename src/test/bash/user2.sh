# Run rest api methods as user 2. Most useful for methods that do not need an input body, like GET and DELETE.
source `dirname $0`/functions.sh $1 $2 ${@:3}

curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/${EXCHANGE_USER}2:$EXCHANGE_PW" $EXCHANGE_URL_ROOT/v1/${org}$resource | $parse