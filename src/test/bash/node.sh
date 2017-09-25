# Run rest api methods as node
source `dirname $0`/functions.sh $1 $2 ${@:3}

curl $copts -X $method -H 'Accept: application/json' -H "Authorization:Basic $EXCHANGE_ORG/$EXCHANGE_NODEAUTH" $EXCHANGE_URL_ROOT/v1/${org}$resource | $parse