# Run rest api methods as anonymous
source `dirname $0`/functions.sh $1 $2 ${@:3}

curl $copts -X $method -H 'Accept: application/json' $EXCHANGE_URL_ROOT/v1/${org}$resource | $parse